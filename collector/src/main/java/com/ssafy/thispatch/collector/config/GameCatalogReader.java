package com.ssafy.thispatch.collector.config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * 수집할 게임 목록을 서비스 DB(서버1)의 {@code game} 테이블에서 읽는다.
 *
 * <p>⚠ 이 모듈에서 <b>서비스 DB 를 보는 곳은 여기 하나뿐</b>이고, 마스터에서만
 * 쓴다. 워커는 서버1 에 닿지도 않는다(EC2 에서 노트북 대역으로 나가는 경로가
 * 없다). 워커는 마스터가 보내준 appid 목록만 받는다.
 *
 * <p>Spring 의 DataSource 를 쓰지 않고 직접 연결한다. 이 모듈의 기본
 * DataSource 는 배치 메타데이터 DB 이고, 두 번째 DataSource 를 등록하면
 * {@code @Primary} 를 붙이고 다니는 일이 따라온다. 여기서 필요한 것은
 * 잡 시작 때 한 번 읽는 SELECT 하나뿐이다.
 *
 * <p>접속은 SSH 터널을 지난다. 서버1 의 5432 는 밖에서 막혀 있고 도커 대역만
 * 열려 있다. 터널은 배치를 돌리는 쪽(21-catalog.sh · 오케스트레이션)이 연다.
 */
public class GameCatalogReader {

    /**
     * ⚠ 리뷰가 하나도 없는 게임은 거른다.
     *
     * <p>18.5만 개 중 6.9만 개(37%)가 리뷰 0개다. 출시 예정이거나 아무도 사지
     * 않은 게임들이다. 이들에게 요청을 보내도 빈 응답만 돌아오는데, 게임마다
     * 첫 호출 1회 + 끝 확인이 붙어 한 바퀴에 수만 콜이 그냥 버려진다.
     *
     * <p>리뷰가 새로 달리면 다음 날 카탈로그 갱신에서 {@code store_review_count}
     * 가 올라가므로 그때부터 대상에 들어온다. 놓치지 않는다.
     */
    private static final String SQL =
            "SELECT appid FROM game WHERE store_review_count > 0 ORDER BY appid";

    /**
     * 공지 수집용 — 리뷰 수 조건을 걸지 않는다.
     *
     * <p>⚠ 리뷰와 기준이 다르다. 리뷰는 리뷰가 0개면 받을 것이 없지만, 공지는
     * <b>리뷰가 없어도 있을 수 있다.</b> 출시 직후라 아직 리뷰는 없는데 패치 공지만
     * 올라온 게임이 그렇다. 여기서 걸러 버리면 그 게임의 패치 이력을 통째로 놓친다.
     */
    private static final String SQL_ALL = "SELECT appid FROM game ORDER BY appid";

    private final String url;
    private final String user;
    private final String password;

    public GameCatalogReader(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    /** 리뷰 수집 대상 — 리뷰가 하나라도 있는 게임. */
    public List<Long> targetAppids() {
        return query(SQL, "수집 대상");
    }

    /** 공지 수집 대상 — 게임 전체. */
    public List<Long> allAppids() {
        return query(SQL_ALL, "게임");
    }

    private List<Long> query(String sql, String what) {
        Properties props = new Properties();
        props.setProperty("user", user);
        props.setProperty("password", password);
        props.setProperty("ApplicationName", "thispatch-collector-manager");
        // 목록 한 번 읽고 끊는다. 오래 붙들고 있을 이유가 없다.
        props.setProperty("connectTimeout", "10");
        props.setProperty("socketTimeout", "60");

        List<Long> out = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(url, props);
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(rs.getLong(1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "서비스 DB 에서 게임 목록을 읽지 못했습니다. SSH 터널이 열려 있는지"
                            + " 확인하세요 (" + url + ")", e);
        }
        if (out.isEmpty()) {
            throw new IllegalStateException(
                    "game 테이블에 " + what + "이 없습니다. 카탈로그를 먼저 채우세요:"
                            + " infra/scripts/21-catalog.sh now");
        }
        return out;
    }
}
