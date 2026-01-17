import axios from "axios";

const http = axios.create({
  baseURL: "http://localhost:8080", // ⚠️ 改成你的 Spring Boot 地址
  timeout: 10_000
});

// 可选：统一日志 / token / header
http.interceptors.request.use((config) => {
  console.log("[HTTP]", config.method?.toUpperCase(), config.url);
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