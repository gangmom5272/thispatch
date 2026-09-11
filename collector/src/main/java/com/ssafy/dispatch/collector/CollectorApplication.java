package com.ssafy.dispatch.collector;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 스팀 수집 배치.
 *
 * <p>노트북 5대에 같은 jar 를 올리고 프로파일로 역할을 가른다.
 *
 * <pre>
 *   마스터  --spring.profiles.active=manager   작업을 나눠 보낸다
 *   워커    --spring.profiles.active=worker    받아서 수집한다
 * </pre>
 *
 * <p>스팀 API 호출은 반드시 <b>워커에서</b> 나가야 한다. 한 IP 에서 몰아치면
 * 스팀이 403 으로 막는다(서버1 에서 실제로 겪었다). 마스터가 대신 호출해주는
 * 구조로 만들면 노드를 나눈 의미가 없어진다.
 *
 * <p>수집 결과도 마스터를 거치지 않는다. 워커가 HDFS 에 직접 쓴다.
 * 마스터를 경유하면 느린 무선 구간을 두 번 탄다.
 */
@SpringBootApplication
public class CollectorApplication {

    public static void main(String[] args) {
        SpringApplication.run(CollectorApplication.class, args);
    }
}
