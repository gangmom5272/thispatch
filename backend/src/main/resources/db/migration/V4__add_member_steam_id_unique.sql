-- NULL은 여러 행에서 허용한다. 기존 중복 Steam ID는 자동으로 삭제하거나 병합하지 않는다.
ALTER TABLE "member"
    ADD CONSTRAINT "uk_member_steam_id" UNIQUE ("steam_id");
