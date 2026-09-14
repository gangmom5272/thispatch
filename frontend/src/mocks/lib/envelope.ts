function nowSeoul() {
  return new Date().toLocaleString("sv-SE", { timeZone: "Asia/Seoul" }).replace("T", " ")
}

export function okEnvelope<T>(data: T, message = "성공했습니다.") {
  return {
    code: "200",
    message,
    responsedAt: nowSeoul(),
    data,
    success: true as const,
  }
}

export function okMessage(message: string) {
  return {
    code: "200",
    message,
    responsedAt: nowSeoul(),
    success: true as const,
  }
}

export function errorBody(code: string, message: string) {
  return {
    code,
    message,
    responsedAt: nowSeoul(),
  }
}
