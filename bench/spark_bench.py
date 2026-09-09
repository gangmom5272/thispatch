#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Spark 집계 시간 측정

답하려는 것
  1. 리뷰 N건을 집계하는 데 몇 분 걸리는가
  2. 병렬도(코어 수)를 올리면 얼마나 줄어드는가 = 스케일아웃 효율
  3. 1.5억 건으로 환산하면 몇 시간인가  ->  하루 예산에 들어가는가

측정하는 집계는 실제 설계와 같은 것 3개입니다.
  daily_stat      (appid, 날짜, 작성구분)  전체 행 스캔 + 큰 셔플
  language_stat   (appid, 언어)            중간 셔플
  band_stat       appid 별 플레이타임 4분위 + 밴드 배정   가장 비쌈

사용법
  python spark_bench.py convert                     JSONL.gz -> Parquet (1회)
  python spark_bench.py agg --parallel 1,2,4,8,16   병렬도별 집계 시간
  python spark_bench.py agg --parallel 8 --source json   Parquet 없이 JSONL 직접
"""

import argparse
import csv
import io
import os
import glob
import shutil
import sys
import time
from datetime import datetime

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", errors="replace")

HERE = os.path.dirname(os.path.abspath(__file__))
JSON_DIR = os.path.join(HERE, "out", "reviews")
PARQUET_DIR = os.path.join(HERE, "out", "parquet")
RESULT_CSV = os.path.join(HERE, "out", "spark_bench.csv")

# JDK 17 (시스템에 이미 있는 것)
for cand in (r"C:\Users\SSAFY\.jdks\ms-17.0.20", r"C:\Users\SSAFY\.jdks\openjdk-26.0.2.1"):
    if os.path.isdir(cand):
        os.environ.setdefault("JAVA_HOME", cand)
        break
os.environ.setdefault("PYSPARK_PYTHON", sys.executable)
os.environ.setdefault("PYSPARK_DRIVER_PYTHON", sys.executable)

# Java 17 모듈 제한 때문에 필요합니다. 없으면 파일 읽을 때 JVM 이 죽습니다.
ADD_OPENS = " ".join("--add-opens=java.base/%s=ALL-UNNAMED" % m for m in (
    "java.lang", "java.lang.invoke", "java.io", "java.net", "java.nio",
    "java.util", "java.util.concurrent", "sun.nio.ch", "sun.nio.cs",
    "sun.security.action", "jdk.internal.misc"))
os.environ["JDK_JAVA_OPTIONS"] = ADD_OPENS

def uri(path):
    """Spark 는 백슬래시 경로를 못 읽습니다. file:/// URI 로 바꿉니다."""
    return "file:///" + os.path.abspath(path).replace("\\", "/")


def json_files():
    """
    디렉터리나 glob 을 넘기면 Windows 에서 JVM 이 조용히 죽습니다.
    (winutils 없이 Hadoop 이 파일 목록을 못 만듭니다)
    파일 목록을 파이썬에서 만들어 명시적으로 넘겨야 합니다.
    """
    fs = sorted(glob.glob(os.path.join(JSON_DIR, "*.jsonl.gz")))
    if not fs:
        raise SystemExit("수집된 리뷰가 없습니다: %s" % JSON_DIR)
    return [uri(f) for f in fs]

from pyspark.sql import SparkSession, functions as F  # noqa: E402
from pyspark.sql import types as T  # noqa: E402

REVIEW_SCHEMA = T.StructType([
    T.StructField("recommendationid", T.StringType()),
    T.StructField("author", T.StructType([
        T.StructField("steamid", T.StringType()),
        T.StructField("playtime_at_review", T.LongType()),
        T.StructField("playtime_forever", T.LongType()),
    ])),
    T.StructField("review", T.StringType()),
    T.StructField("voted_up", T.BooleanType()),
    T.StructField("votes_up", T.LongType()),
    T.StructField("timestamp_created", T.LongType()),
    T.StructField("timestamp_updated", T.LongType()),
    T.StructField("_appid", T.LongType()),
    T.StructField("_language", T.StringType()),
])


def spark_session(cores, name):
    return (SparkSession.builder
            .master("local[%d]" % cores)
            .appName(name)
            .config("spark.driver.memory", "12g")
            .config("spark.sql.shuffle.partitions", str(max(8, cores * 4)))
            .config("spark.sql.adaptive.enabled", "true")
            .config("spark.ui.showConsoleProgress", "false")
            .config("spark.sql.session.timeZone", "UTC")
            .config("spark.driver.extraJavaOptions", ADD_OPENS)
            .config("spark.executor.extraJavaOptions", ADD_OPENS)
            .config("spark.ui.enabled", "false")
            .getOrCreate())


def load(spark, source):
    """수집한 리뷰를 집계에 필요한 컬럼만 남겨 읽습니다."""
    if source == "parquet":
        return spark.read.parquet(uri(PARQUET_DIR))
    return project(spark.read.schema(REVIEW_SCHEMA)
                   .json(json_files()))


def project(df):
    return df.select(
        F.col("_appid").cast("long").alias("appid"),
        F.col("recommendationid").cast("long").alias("review_id"),
        F.col("_language").alias("language_code"),
        F.col("voted_up").cast("boolean").alias("voted_up"),
        F.col("votes_up").cast("int").alias("votes_up"),
        F.col("author.playtime_at_review").cast("int").alias("playtime_at_review"),
        F.col("timestamp_created").cast("long").alias("created_ts"),
        F.col("timestamp_updated").cast("long").alias("updated_ts"),
        F.length(F.col("review")).alias("review_len"),
    )


def force(df):
    """결과를 실제로 계산하게 만듭니다. 디스크 I/O 없이 실행만 강제."""
    df.write.format("noop").mode("overwrite").save()


# ---------------------------------------------------------------------------
# 집계 3종 -- 실제 설계와 같은 모양
# ---------------------------------------------------------------------------
def agg_daily(df):
    return (df
            .withColumn("stat_date", F.to_date(F.from_unixtime("updated_ts")))
            .withColumn("channel_type",
                        F.when(F.col("created_ts") == F.col("updated_ts"), "first")
                         .otherwise("edited"))
            .groupBy("appid", "stat_date", "channel_type")
            .agg(F.count("*").alias("review_count"),
                 F.sum(F.col("voted_up").cast("int")).alias("positive_count"))
            .withColumn("positive_pct",
                        F.round(F.col("positive_count") / F.col("review_count") * 100, 2)))


def agg_language(df):
    return (df
            .groupBy("appid", "language_code")
            .agg(F.count("*").alias("review_count"),
                 F.sum(F.col("voted_up").cast("int")).alias("positive_count"),
                 F.avg("playtime_at_review").alias("avg_playtime")))


def agg_band(df):
    """appid 별 플레이타임 4분위를 구하고 밴드를 배정한 뒤 다시 집계합니다."""
    q = (df.groupBy("appid")
           .agg(F.expr("percentile_approx(playtime_at_review, array(0.25,0.5,0.75), 1000)")
                .alias("q")))
    j = df.join(F.broadcast(q), "appid")
    banded = j.withColumn(
        "band_no",
        F.when(F.col("playtime_at_review") <= F.col("q")[0], 1)
         .when(F.col("playtime_at_review") <= F.col("q")[1], 2)
         .when(F.col("playtime_at_review") <= F.col("q")[2], 3)
         .otherwise(4))
    return (banded.groupBy("appid", "band_no")
            .agg(F.count("*").alias("review_count"),
                 F.sum(F.col("voted_up").cast("int")).alias("positive_count"),
                 F.min("playtime_at_review").alias("band_lo"),
                 F.max("playtime_at_review").alias("band_hi")))


AGGS = [("daily_stat", agg_daily), ("language_stat", agg_language), ("band_stat", agg_band)]


# ---------------------------------------------------------------------------
def do_convert(cores):
    if os.path.isdir(PARQUET_DIR):
        shutil.rmtree(PARQUET_DIR)
    spark = spark_session(cores, "convert")
    t = time.time()
    df = project(spark.read.schema(REVIEW_SCHEMA)
                 .json(json_files()))
    (df.repartition("appid")
       .write.mode("overwrite").partitionBy("appid").parquet(uri(PARQUET_DIR)))
    sec = time.time() - t
    n = spark.read.parquet(uri(PARQUET_DIR)).count()
    size = sum(os.path.getsize(os.path.join(r, f))
               for r, _, fs in os.walk(PARQUET_DIR) for f in fs)
    print("\n변환 완료")
    print("  리뷰        %s건" % "{:,}".format(n))
    print("  소요        %.1f분 (코어 %d)" % (sec / 60, cores))
    print("  Parquet     %.2f GB  (원본 JSONL.gz 4.3GB)" % (size / 1024 ** 3))
    print("  행당        %.1f B" % (size / max(1, n)))
    spark.stop()
    return n


def do_agg(core_list, source):
    """
    코어 수별로 세 가지를 잽니다.
      read   디스크에서 읽고 파싱하는 시간 (캐시 없음)
      agg    캐시된 데이터로 집계 3종 (순수 셔플/계산)
      실질   read + agg = 일일 배치가 실제로 쓰는 시간
    """
    rows, total_rows = [], None
    for cores in core_list:
        spark = spark_session(cores, "agg-%d" % cores)
        spark.sparkContext.setLogLevel("ERROR")

        t = time.time()
        raw = load(spark, source)
        total_rows = raw.count()                 # 읽기 + 파싱
        read_sec = time.time() - t

        df = load(spark, source).cache()
        df.count()                               # 캐시 워밍
        per, t0 = {}, time.time()
        for label, fn in AGGS:
            t = time.time()
            force(fn(df))
            per[label] = time.time() - t
        agg_sec = time.time() - t0
        print("  코어 %2d   read %6.1f초   agg %6.1f초   실질 %6.1f초"
              % (cores, read_sec, agg_sec, read_sec + agg_sec))
        rows.append(dict(cores=cores, rows=total_rows, read=read_sec,
                         agg=agg_sec, total=read_sec + agg_sec, **per))
        spark.stop()

    base = rows[0]["total"] if rows else 0
    scale = 150_000_000 / max(1, total_rows)
    print()
    print("=" * 78)
    print("Spark 집계 -- 리뷰 %s건 · %s (Parquet 아님 = 상한값)"
          % ("{:,}".format(total_rows), source))
    print("=" * 78)
    print("  코어   read    daily  language   band    실질     스케일    1.5억 환산")
    print("  " + "-" * 70)
    for r in rows:
        print("  %4d  %6.1f  %6.1f   %6.1f  %6.1f  %6.1f초   x%.2f    %6.1f분" % (
            r["cores"], r["read"], r["daily_stat"], r["language_stat"],
            r["band_stat"], r["total"], base / r["total"] if r["total"] else 0,
            r["total"] * scale / 60))
    print()
    if rows:
        best = min(rows, key=lambda r: r["total"])
        h = best["total"] * scale / 3600
        print("  최적 %d코어 → 1.5억 건 %.0f분 (%.2f시간)" % (best["cores"], h * 60, h))
        tot = 14.0 + 4.6 + 1.5 + h
        print("  하루 예산   수집 14.0h + 임베딩 4.6h + 요약 1.5h + 집계 %.2fh = %.1fh / 24h"
              % (h, tot))
        print("  판정        %s" % ("들어감" if tot < 24 else "초과 -- 재설계 필요"))
        print()
        print("  주의  노트북 1대의 코어 분할이라 진짜 다중 노드보다 스케일 효율이 낮습니다.")
        print("        JSON 파싱 기준이라 Parquet 을 쓰면 이보다 빨라집니다.")

    with open(RESULT_CSV, "w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=["cores", "rows", "read", "daily_stat",
                                          "language_stat", "band_stat", "agg", "total"])
        w.writeheader()
        for r in rows:
            w.writerow(r)
    print()
    print("  상세: %s" % RESULT_CSV)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("mode", choices=["convert", "agg"])
    ap.add_argument("--parallel", default="1,2,4,8",
                    help="집계에 쓸 코어 수 목록 (기본 1,2,4,8)")
    ap.add_argument("--source", default="parquet", choices=["parquet", "json"])
    ap.add_argument("--cores", type=int, default=16, help="convert 에 쓸 코어 수")
    a = ap.parse_args()
    print("JAVA_HOME = %s" % os.environ.get("JAVA_HOME"))
    if a.mode == "convert":
        do_convert(a.cores)
    else:
        do_agg([int(x) for x in a.parallel.split(",")], a.source)
