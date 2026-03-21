import axios from "axios";

const http = axios.create({
  baseURL: "http://localhost:8001",
  timeout: 600_000
});

// 統一管理 Token（僅存原始 JWT，不含 Bearer 前綴）
let authToken = null;

export function setAuthToken(token) {
  authToken = token || null;
}

export function getAuthToken() {
  return authToken;
}

function toBearerToken(token) {
  if (!token || typeof token !== "string") return "";
  return token.startsWith("Bearer ") ? token : `Bearer ${token}`;
}

// 統一日誌 / token / header
http.interceptors.request.use((config) => {
  console.log("[HTTP]", config.method?.toUpperCase(), config.url);

  if (authToken) {
    config.headers = config.headers || {};
    config.headers.Authorization = toBearerToken(authToken);
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
