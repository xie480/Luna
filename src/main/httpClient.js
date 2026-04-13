import axios from "axios";
import { EventEmitter } from "events";

const http = axios.create({
  baseURL: "http://localhost:8001",
  timeout: 600_000
});

// 統一管理 Token（僅存原始 JWT，不含 Bearer 前綴）
let authToken = null;
const authEvents = new EventEmitter();
let authExpiredNotified = false;

export function setAuthToken(token) {
  authToken = token || null;
  if (authToken) {
    authExpiredNotified = false;
  }
}

export function getAuthToken() {
  return authToken;
}

export function onAuthExpired(listener) {
  authEvents.on("expired", listener);
  return () => authEvents.off("expired", listener);
}

export function getErrorMessage(error, fallback = "Request failed") {
  const responseData = error?.response?.data;

  if (typeof responseData === "string" && responseData.trim()) {
    return responseData.trim();
  }

  if (responseData && typeof responseData === "object") {
    const text =
      responseData.message ||
      responseData.reason ||
      responseData.error ||
      responseData.reply;

    if (typeof text === "string" && text.trim()) {
      return text.trim();
    }
  }

  if (typeof error?.message === "string" && error.message.trim()) {
    return error.message.trim();
  }

  return fallback;
}

function toBearerToken(token) {
  if (!token || typeof token !== "string") return "";
  return token.startsWith("Bearer ") ? token : `Bearer ${token}`;
}

function shouldSkipDataUnwrap(config) {
  const accept = String(config?.headers?.Accept || config?.headers?.accept || "");
  if (accept.includes("text/event-stream")) return true;
  if (config?.responseType === "stream") return true;
  return false;
}

function shouldNotifyAuthExpired(error) {
  if (error?.response?.status !== 401) return false;

  const requestUrl = String(error?.config?.url || "");
  if (!requestUrl) return false;

  if (requestUrl.includes("/auth/login") || requestUrl.includes("/auth/logout")) {
    return false;
  }

  return !!authToken;
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
  (res) => {
    if (shouldSkipDataUnwrap(res.config)) {
      return res;
    }
    return res.data;
  },
  (err) => {
    console.error("[HTTP ERROR]", err.message);
    if (shouldNotifyAuthExpired(err) && !authExpiredNotified) {
      authExpiredNotified = true;
      const message = getErrorMessage(err, "unauthorized");
      authToken = null;
      authEvents.emit("expired", {
        url: String(err?.config?.url || ""),
        message,
      });
    }
    throw err;
  }
);

export default http;
