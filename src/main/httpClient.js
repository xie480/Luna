import axios from "axios";

const http = axios.create({
  baseURL: "http://localhost:8001", // ⚠️ 改成你的 Spring Boot 地址
  timeout: 600_000 // [Fix] 調整為 60秒，避免過長超時
});

// 統一管理 Token
let authToken = null;

export function setAuthToken(token) {
  authToken = token;
}

export function getAuthToken() {
  return authToken;
}

// 統一日誌 / token / header
http.interceptors.request.use((config) => {
  console.log("[HTTP]", config.method?.toUpperCase(), config.url);
  if (authToken) {
    config.headers = config.headers || {};
    config.headers.Authorization = authToken;
  }
  return config;
});

http.interceptors.response.use(
  (res) => res.data,
  (err) => {
    console.error("[HTTP ERROR]", err.message);
    throw err;
  }
);

export default http;
