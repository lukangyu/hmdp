import http from "k6/http";
import { Counter, Trend } from "k6/metrics";

export const options = {
  scenarios: {
    seckill_rush: {
      executor: "shared-iterations",
      vus: Number(__ENV.VUS || 5200),
      iterations: Number(__ENV.ITERATIONS || 5200),
      maxDuration: __ENV.MAX_DURATION || "3m",
    },
  },
  summaryTrendStats: ["avg", "min", "med", "p(90)", "p(95)", "max"],
};

const baseUrl = __ENV.BASE_URL || "http://host.docker.internal:8081";
const voucherId = __ENV.VOUCHER_ID || "1";
const userCount = Number(__ENV.USER_COUNT || 1000);

const DUPLICATE_MSG = "\u4e0d\u80fd\u91cd\u590d\u4e0b\u5355";
const STOCK_MSG = "\u5e93\u5b58\u4e0d\u8db3";

export const seckill_ok = new Counter("seckill_ok");
export const seckill_dup_rejected = new Counter("seckill_dup_rejected");
export const seckill_stock_rejected = new Counter("seckill_stock_rejected");
export const seckill_other_fail = new Counter("seckill_other_fail");
export const seckill_api_latency = new Trend("seckill_api_latency", true);

function phoneOf(i) {
  return `138${String(i).padStart(8, "0")}`;
}

function tryParseToken(res) {
  try {
    const data = res.json();
    if (data && data.success === true && data.data) {
      return String(data.data);
    }
  } catch (e) {
    // ignore parse errors and return null
  }
  return null;
}

export function setup() {
  const headers = { "Content-Type": "application/json" };
  const tokens = [];

  for (let i = 1; i <= userCount; i += 1) {
    const payload = JSON.stringify({
      phone: phoneOf(i),
      code: "123456",
      password: "",
    });
    const res = http.post(`${baseUrl}/user/login`, payload, { headers });
    const token = tryParseToken(res);
    if (!token) {
      throw new Error(`login failed at user index ${i}, status=${res.status}, body=${res.body}`);
    }
    tokens.push(token);
  }

  return { tokens };
}

export default function (data) {
  const tokens = data.tokens;
  const token = tokens[(__VU - 1) % tokens.length];
  const url = `${baseUrl}/voucher-order/seckill/${voucherId}`;
  const res = http.post(url, null, { headers: { authorization: token } });

  seckill_api_latency.add(res.timings.duration);
  const body = res.body || "";

  if (res.status === 200 && body.includes('"success":true')) {
    seckill_ok.add(1);
    return;
  }
  if (body.includes(DUPLICATE_MSG)) {
    seckill_dup_rejected.add(1);
    return;
  }
  if (body.includes(STOCK_MSG)) {
    seckill_stock_rejected.add(1);
    return;
  }
  seckill_other_fail.add(1);
}
