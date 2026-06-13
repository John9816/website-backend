import bcrypt from "bcryptjs";
import { initialCredits, userCreditView } from "./credits";
import { sha256Hex, signJwt, verifyJwt } from "./crypto";
import { HttpError, AuthUser, Env } from "./types";

const DEFAULT_SECRET_WARNING = "dev-only-change-me";

interface UserRow {
  id: number;
  username: string;
  email?: string | null;
  password_hash: string;
  role: string;
  credits?: number;
}

const QQ_EMAIL_PATTERN = /^[1-9]\d{4,10}@qq\.com$/i;

export async function getAuthUser(request: Request, env: Env): Promise<AuthUser | null> {
  const header = request.headers.get("authorization");
  if (!header?.startsWith("Bearer ")) {
    return null;
  }
  const payload = await verifyJwt(header.slice("Bearer ".length), jwtSecret(env));
  const id = Number(payload?.sub);
  if (!Number.isFinite(id)) {
    return null;
  }
  const cached = await env.APP_KV.get<AuthUser>(`auth:user:${id}`, "json");
  if (cached) {
    return cached;
  }
  const row = await env.DB.prepare("SELECT id, username, role FROM users WHERE id = ?").bind(id).first<AuthUser>();
  if (row) {
    await env.APP_KV.put(`auth:user:${id}`, JSON.stringify(row), { expirationTtl: 300 });
  }
  return row ?? null;
}

export async function login(env: Env, username: string, password: string) {
  const user = await env.DB.prepare("SELECT id, username, password_hash, role FROM users WHERE username = ?")
    .bind(username)
    .first<UserRow>();
  if (!user || !await passwordMatches(password, user.password_hash)) {
    throw new HttpError(401, "Invalid username or password");
  }
  await upgradePasswordHash(env, user, password);
  return loginView(env, user);
}

export async function register(env: Env, username: string, password: string, email: string) {
  const normalizedUsername = username.trim();
  const normalizedEmail = email.trim().toLowerCase();
  if (!normalizedUsername || normalizedUsername.length < 3 || !password || password.length < 6) {
    throw new HttpError(400, "username or password is invalid");
  }
  if (!QQ_EMAIL_PATTERN.test(normalizedEmail)) {
    throw new HttpError(400, "email must be a valid QQ email");
  }
  const credits = await initialCredits(env);
  try {
    const result = await env.DB.prepare("INSERT INTO users(username, email, password_hash, role, credits) VALUES(?, ?, ?, 'USER', ?)")
      .bind(normalizedUsername, normalizedEmail, await passwordHash(password), credits)
      .run();
    return loginView(env, {
      id: Number(result.meta.last_row_id),
      username: normalizedUsername,
      email: normalizedEmail,
      password_hash: "",
      role: "USER",
      credits
    });
  } catch (error) {
    const message = String((error as Error | undefined)?.message || "");
    if (message.includes("users.email")) {
      throw new HttpError(409, "QQ email already exists");
    }
    throw new HttpError(409, "username already exists");
  }
}

export async function changePassword(env: Env, userId: number, oldPassword: string, newPassword: string): Promise<void> {
  const row = await env.DB.prepare("SELECT id, username, password_hash, role FROM users WHERE id = ?").bind(userId).first<UserRow>();
  if (!row || !await passwordMatches(oldPassword, row.password_hash)) {
    throw new HttpError(400, "oldPassword is incorrect");
  }
  if (!newPassword || newPassword.length < 6) {
    throw new HttpError(400, "newPassword must be at least 6 characters");
  }
  await env.DB.prepare("UPDATE users SET password_hash = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?")
    .bind(await passwordHash(newPassword), userId)
    .run();
  await env.APP_KV.delete(`auth:user:${userId}`);
}

export async function ensureAdmin(env: Env): Promise<void> {
  const total = await env.DB.prepare("SELECT COUNT(*) AS total FROM users").first<{ total: number }>();
  if (Number(total?.total ?? 0) > 0) {
    return;
  }
  const username = env.ADMIN_DEFAULT_USERNAME || "admin";
  const password = env.ADMIN_DEFAULT_PASSWORD || "admin123";
  const credits = await initialCredits(env);
  await env.DB.prepare("INSERT INTO users(username, password_hash, role, credits) VALUES(?, ?, 'ADMIN', ?)")
    .bind(username, await passwordHash(password), credits)
    .run();
}

export async function currentUserView(env: Env, user: AuthUser) {
  const view = await userCreditView(env, user.id);
  return {
    id: user.id,
    username: user.username,
    role: user.role,
    canManageSystemConfig: user.role === "ADMIN",
    ...view
  };
}

async function loginView(env: Env, user: UserRow) {
  const expiresAt = Math.floor(Date.now() / 1000) + 60 * 60 * 24 * 7;
  const token = await signJwt(
    { sub: String(user.id), username: user.username, role: user.role, exp: expiresAt },
    jwtSecret(env)
  );
  return {
    token,
    user: await currentUserView(env, user),
    username: user.username,
    role: user.role
  };
}

async function passwordHash(password: string): Promise<string> {
  return `sha256:${await sha256Hex(password)}`;
}

async function passwordMatches(password: string, storedHash: string): Promise<boolean> {
  if (storedHash.startsWith("sha256:")) {
    return storedHash === await passwordHash(password);
  }
  if (storedHash.startsWith("$2a$") || storedHash.startsWith("$2b$") || storedHash.startsWith("$2y$")) {
    return bcrypt.compare(password, storedHash);
  }
  return false;
}

async function upgradePasswordHash(env: Env, user: UserRow, password: string): Promise<void> {
  if (user.password_hash.startsWith("sha256:")) {
    return;
  }
  const nextHash = await passwordHash(password);
  await env.DB.prepare("UPDATE users SET password_hash = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?")
    .bind(nextHash, user.id)
    .run();
  user.password_hash = nextHash;
  await env.APP_KV.delete(`auth:user:${user.id}`);
}

function jwtSecret(env: Env): string {
  return env.JWT_SECRET || DEFAULT_SECRET_WARNING;
}
