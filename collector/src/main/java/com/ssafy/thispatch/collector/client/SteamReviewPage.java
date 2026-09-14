package com.ssafy.thispatch.collector.client;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/**
 * Steam 리뷰 한 페이지. 리뷰의 중첩 구조, 필드명과 JSON 값 타입을 그대로 보존한다.
 *
 * @param nextCursor 다음 요청에 사용할 인코딩 전 cursor. 빈 페이지에서는 없을 수 있다.
 */
public record SteamReviewPage(List<ObjectNode> reviews, String nextCursor) {

    public SteamReviewPage {
        reviews = List.copyOf(reviews);
    }
}
