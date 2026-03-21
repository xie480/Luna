import axios from 'axios';
import router from "../router";

const request = axios.create({
  baseURL: '/api',
  timeout: 500000,
  headers: {
    'Content-Type': 'application/json'
  }
});

function toBearer(token) {
  if (!token || typeof token !== 'string') return '';
  return token.startsWith('Bearer ') ? token : `Bearer ${token}`;
}

request.interceptors.request.use(
  config => {
    const result = JSON.parse(localStorage.getItem('loginUser'));
    if (result && result.token) {
      config.headers.Authorization = toBearer(result.token);
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
      if (error.response.status === 401) {
        localStorage.removeItem('loginUser');
        router.push('/login');
      }
    }
    return Promise.reject(error);
  }
);

export default request;
