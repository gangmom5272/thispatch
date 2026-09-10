CREATE TABLE "patch_stat" (
	"gid"	VARCHAR(20)		NOT NULL,
	"appid"	BIGINT		NOT NULL,
	"patched_at"	TIMESTAMPTZ		NOT NULL,
	"before_review_count"	INTEGER		NOT NULL,
	"before_positive_pct"	NUMERIC(5,2)		NULL,
	"after_review_count"	INTEGER		NOT NULL,
	"after_positive_pct"	NUMERIC(5,2)		NULL,
	"delta_pct"	NUMERIC(5,2)		NULL,
	"stat_date"	DATE		NULL,
	"aggregated_at"	TIMESTAMPTZ		NULL
);

CREATE TABLE "tag" (
	"tag_id"	INTEGER		NOT NULL,
	"name_ko"	VARCHAR(100)		NOT NULL,
	"collected_at"	TIMESTAMPTZ		NULL
);

CREATE TABLE "my_game" (
	"member_id"	BIGINT		NOT NULL,
	"appid"	BIGINT		NOT NULL,
	"created_at"	TIMESTAMPTZ		NOT NULL,
	PRIMARY KEY ("member_id", "appid")
);

CREATE TABLE "daily_stat" (
	"daily_stat_id"	BIGSERIAL		NOT NULL,
	"appid"	BIGINT		NOT NULL,
	"review_count"	INTEGER		NOT NULL,
	"negative_count"	INTEGER		NULL,
	"new_review_count"	INTEGER		NOT NULL,
	"new_positive_count"	INTEGER		NOT NULL,
	"edited_review_count"	INTEGER		NOT NULL,
	"edited_positive_count"	INTEGER		NOT NULL,
	"stat_date"	DATE		NOT NULL,
	"aggregated_at"	TIMESTAMPTZ		NULL
);

CREATE TABLE "topic" (
	"topic_id"	SMALLINT		NOT NULL,
	"name_ko"	VARCHAR(50)		NOT NULL,
	"centroid"	vector(768)		NULL,
	"sim_threshold"	NUMERIC(4,3)		NOT NULL
);

CREATE TABLE "code" (
	"code_id"	SMALLSERIAL		NOT NULL,
	"code_group"	VARCHAR(20)		NOT NULL,
	"code_value"	VARCHAR(30)		NOT NULL,
	"name_ko"	VARCHAR(50)		NOT NULL,
	"sort_order"	SMALLINT		NOT NULL,
	"is_active"	BOOLEAN		NOT NULL,
	"definition"	TEXT		NULL
);

CREATE TABLE "news" (
	"gid"	VARCHAR(20)		NOT NULL,
	"appid"	BIGINT		NOT NULL,
	"title"	VARCHAR(500)		NOT NULL,
	"contents"	TEXT		NOT NULL,
	"url"	VARCHAR(1000)		NULL,
	"published_at"	TIMESTAMPTZ		NOT NULL,
	"is_developer_post"	BOOLEAN		NULL,
	"feed_tags"	VARCHAR(300)		NULL,
	"is_patch"	BOOLEAN		NOT NULL,
	"patch_reason"	TEXT		NULL,
	"collected_at"	TIMESTAMPTZ		NOT NULL
);

CREATE TABLE "band_topic_stat" (
	"band_stat_id"	BIGINT		NOT NULL,
	"topic_id"	SMALLINT		NOT NULL,
	"negative_count"	INTEGER		NOT NULL,
	PRIMARY KEY ("band_stat_id", "topic_id")
);

CREATE TABLE "band_stat" (
	"band_stat_id"	BIGSERIAL		NOT NULL,
	"appid"	BIGINT		NOT NULL,
	"band_no"	SMALLINT		NOT NULL,
	"playtime_from"	INTEGER		NOT NULL,
	"playtime_to"	INTEGER		NULL,
	"review_count"	INTEGER		NOT NULL,
	"positive_count"	INTEGER		NOT NULL,
	"aggregated_at"	TIMESTAMPTZ		NULL
);

CREATE TABLE "recent_review" (
	"review_id"	BIGSERIAL		NOT NULL,
	"recommendationid"	BIGINT		NOT NULL,
	"appid"	BIGINT		NOT NULL,
	"review_text"	TEXT		NOT NULL,
	"voted_up"	BOOLEAN		NOT NULL,
	"votes_up"	INTEGER		NOT NULL,
	"playtime_at_review"	INTEGER		NULL,
	"language_code"	VARCHAR(20)		NOT NULL,
	"band_no"	SMALLINT		NULL,
	"created_ts"	TIMESTAMPTZ		NOT NULL,
	"updated_ts"	TIMESTAMPTZ		NOT NULL
);

CREATE TABLE "batch_log" (
	"batch_log_id"	BIGSERIAL		NOT NULL,
	"batch_job_id"	INTEGER		NOT NULL,
	"started_at"	TIMESTAMPTZ		NOT NULL,
	"finished_at"	TIMESTAMPTZ		NULL,
	"status"	VARCHAR(10)		NOT NULL,
	"input_count"	INTEGER		NULL,
	"output_count"	INTEGER		NULL,
	"error_count"	INTEGER		NULL,
	"error_summary"	TEXT		NULL
);

CREATE TABLE "game_tag" (
	"appid"	BIGINT		NOT NULL,
	"tag_id"	INTEGER		NOT NULL,
	"weight"	INTEGER		NOT NULL,
	PRIMARY KEY ("appid", "tag_id")
);

CREATE TABLE "game" (
	"appid"	BIGINT		NOT NULL,
	"name"	VARCHAR(500)		NOT NULL,
	"developer"	VARCHAR(500)		NULL,
	"publisher"	VARCHAR(500)		NULL,
	"short_description"	TEXT		NULL,
	"store_url_path"	VARCHAR(300)		NULL,
	"release_ts"	TIMESTAMPTZ		NULL,
	"is_early_access"	BOOLEAN		NULL,
	"is_coming_soon"	BOOLEAN		NULL,
	"store_review_count"	INTEGER		NULL,
	"store_positive_pct"	SMALLINT		NULL,
	"capsule_path"	VARCHAR(200)		NULL,
	"collected_at"	TIMESTAMPTZ		NOT NULL
);

CREATE TABLE "language" (
	"language_code"	VARCHAR(20)		NOT NULL,
	"name_ko"	VARCHAR(20)		NOT NULL
);

CREATE TABLE "language_stat" (
	"language_stat_id"	BIGSERIAL		NOT NULL,
	"appid"	BIGINT		NOT NULL,
	"language_code"	VARCHAR(20)		NOT NULL,
	"review_count"	INTEGER		NOT NULL,
	"positive_count"	INTEGER		NOT NULL,
	"aggregated_at"	TIMESTAMPTZ		NULL
);

CREATE TABLE "member" (
	"member_id"	BIGSERIAL		NOT NULL,
	"login_type"	VARCHAR(10)		NOT NULL,
	"email"	VARCHAR(255)		NULL,
	"password"	CHAR(64)		NULL,
	"steam_id"	NUMERIC(20, 0)		NULL,
	"nickname"	VARCHAR(50)		NULL,
	"status"	VARCHAR(10)		NOT NULL,
	"created_at"	TIMESTAMPTZ		NOT NULL,
	"updated_at"	TIMESTAMPTZ		NULL
);

CREATE TABLE "batch_job" (
	"batch_job_id"	INTEGER		NOT NULL,
	"job_name"	VARCHAR(50)		NOT NULL,
	"description"	TEXT		NULL,
	"is_active"	Boolean		NOT NULL,
	"created_at"	TIMESTAMPTZ		NULL,
	"updated_at"	TIMESTAMPTZ		NULL
);

CREATE TABLE "patch_change" (
	"gid"	VARCHAR(20)		NOT NULL,
	"change_type_id"	SMALLINT		NULL,
	"direction_id"	SMALLINT		NULL,
	"player_impact_id"	SMALLINT		NULL,
	"normalized_summary"	TEXT		NULL,
	"embedding"	vector(768)		NOT NULL,
	"processed_at"	TIMESTAMPTZ		NULL
);

CREATE TABLE "review_topic" (
	"review_id"	BIGINT		NOT NULL,
	"topic_id"	SMALLINT		NOT NULL,
	PRIMARY KEY ("review_id", "topic_id")
);

