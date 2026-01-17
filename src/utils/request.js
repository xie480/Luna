import axios from 'axios';
import router from "../router";

const request = axios.create({
  baseURL: '/api',
  timeout: 500000,
  headers: {
    'Content-Type': 'application/json' // 设置默认的Content-Type
  }
});

request.interceptors.request.use(
  config => {
    const result = JSON.parse(localStorage.getItem('loginUser'));
    if (result && result.token) {
      config.headers.token = result.token;
    }
    return config;
  },
  error => {
    return Promise.reject(error);
  }
);

request.interceptors.response.use(
  response => {
    return response.data;
  },
  error => {
    if (error.response) {
      if (error.response.status == 401) {
        router.push('/login');
      }
    } else if (error.request) {
    } else {
    }
    return Promise.reject(error);
  }
);

export default request;