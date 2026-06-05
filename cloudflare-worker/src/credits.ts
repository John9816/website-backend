import { ok, requireUser } from "./http";
import { Env, HttpError, RequestContext } from "./types";

export const CFG_IMAGE_CREDIT_COST = "image.generate.credit_cost";
export const CFG_CHECKIN_REWARD_CREDITS = "user.checkin.reward_credits";
export const CFG_REGISTER_INITIAL_CREDITS = "user.register.initial_credits";

export async function getIntConfig(env: Env, key: string, fallback: number): Promise<number> {
  const row = await env.DB.prepare("SELECT config_value FROM sys_config WHERE config_key = ?")
    .bind(key)
    .first<{ config_value: string }>();
  const value = Number.parseInt(String(row?.config_value ?? ""), 10);
  return Number.isFinite(value) && value >= 0 ? value : fallback;
}

export async function initialCredits(env: Env): Promise<number> {
  return getIntConfig(env, CFG_REGISTER_INITIAL_CREDITS, 10);
}

export async function imageCreditCost(env: Env): Promise<number> {
  return getIntConfig(env, CFG_IMAGE_CREDIT_COST, 1);
}

export async function checkInRewardCredits(env: Env): Promise<number> {
  return getIntConfig(env, CFG_CHECKIN_REWARD_CREDITS, 5);
}

export async function userCreditView(env: Env, userId: number) {
  const [user, cost, reward] = await Promise.all([
    env.DB.prepare("SELECT credits FROM users WHERE id = ?").bind(userId).first<{ credits: number }>(),
    imageCreditCost(env),
    checkInRewardCredits(env)
  ]);
  if (!user) {
    throw new HttpError(404, "User not found");
  }
  const today = todayUtc();
  const checkIn = await env.DB.prepare(
    "SELECT check_in_date FROM user_check_in WHERE user_id = ? ORDER BY check_in_date DESC LIMIT 1"
  ).bind(userId).first<{ check_in_date: string }>();
  const lastCheckInDate = checkIn?.check_in_date ?? null;
  return {
    credits: Number(user.credits ?? 0),
    imageCreditCost: cost,
    dailyCheckInReward: reward,
    checkedInToday: lastCheckInDate === today,
    lastCheckInDate
  };
}

export async function credits(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  return ok(await userCreditView(ctx.env, user.id));
}

export async function checkIn(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  const reward = await checkInRewardCredits(ctx.env);
  const today = todayUtc();
  const inserted = await ctx.env.DB.prepare(
    "INSERT OR IGNORE INTO user_check_in(user_id, check_in_date, reward_credits) VALUES(?, ?, ?)"
  ).bind(user.id, today, reward).run();
  if (Number(inserted.meta.changes ?? 0) === 0) {
    throw new HttpError(400, "今日已签到，请明天再来");
  }
  if (reward > 0) {
    await ctx.env.DB.prepare("UPDATE users SET credits = credits + ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?")
      .bind(reward, user.id)
      .run();
  }
  return ok(await userCreditView(ctx.env, user.id));
}

export async function chargeCredits(env: Env, userId: number, amount: number): Promise<void> {
  if (amount <= 0) {
    return;
  }
  const result = await env.DB.prepare(
    "UPDATE users SET credits = credits - ?, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND credits >= ?"
  ).bind(amount, userId, amount).run();
  if (Number(result.meta.changes ?? 0) === 0) {
    throw new HttpError(400, `积分不足，本次需要 ${amount} 积分`);
  }
}

export async function refundCredits(env: Env, userId: number, taskId: number): Promise<void> {
  const task = await env.DB.prepare(
    "SELECT credit_cost, credit_refunded FROM image_generation_task WHERE id = ? AND user_id = ?"
  ).bind(taskId, userId).first<{ credit_cost: number; credit_refunded: number }>();
  const amount = Number(task?.credit_cost ?? 0);
  if (!task || amount <= 0 || Number(task.credit_refunded ?? 0) === 1) {
    return;
  }
  await env.DB.prepare("UPDATE users SET credits = credits + ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?")
    .bind(amount, userId)
    .run();
  await env.DB.prepare("UPDATE image_generation_task SET credit_refunded = 1, updated_at = CURRENT_TIMESTAMP WHERE id = ?")
    .bind(taskId)
    .run();
}

export function todayUtc(): string {
  return new Date().toISOString().slice(0, 10);
}
