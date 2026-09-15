-- 기존 중복 이메일은 자동 변경하거나 삭제하지 않고 migration을 실패시킨다.
ALTER TABLE "member"
    ADD CONSTRAINT "uk_member_email" UNIQUE ("email");
