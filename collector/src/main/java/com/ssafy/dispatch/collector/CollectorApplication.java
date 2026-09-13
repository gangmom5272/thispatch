package com.ssafy.dispatch.collector;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 스팀 수집 배치.
 *
 * <p>노트북 5대에 같은 jar 를 올리고 프로파일로 역할을 가른다.
 *
 * <pre>
 *   마스터  --spring.profiles.active=manager   작업을 나눠 보내고 끝나면 종료
 *   워커    --spring.profiles.active=worker    받아서 수집한다. 계속 떠 있는다
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
        ConfigurableApplicationContext ctx = SpringApplication.run(CollectorApplication.class, args);

        // 매니저는 잡이 끝나면 프로세스도 끝나야 한다.
        //
        // 그냥 두면 RabbitMQ 연결 스레드가 살아 있어서 JVM 이 안 죽는다(실측).
        // 오전 9시 타이머로 돌릴 때 프로세스가 안 끝나면 다음 날 것과 겹친다.
        //
        // 워커는 반대로 계속 떠서 큐를 들어야 하므로 건드리지 않는다.
        if (ctx.getEnvironment().matchesProfiles("manager")) {
            System.exit(SpringApplication.exit(ctx));
        }
    }
}
