'use client';

import React, { Suspense, useEffect, useRef, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { Eye, EyeOff, LockKeyhole, ShieldCheck, Sparkles } from 'lucide-react';
import { toast } from 'sonner';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
} from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { sanitizeNextPath } from '@/lib/auth/permissions';
import { isDemoAccountsEnabled } from '@/lib/env';
import { formatApiErrorMessage } from '@/lib/types/problem';
import { useAuth } from '@/providers/auth-provider';

interface DemoAccountOption {
  label: string;
  roleBadge: string;
  department: string;
  email: string;
  password: string;
}

const DEMO_ACCOUNTS: readonly DemoAccountOption[] = [
  {
    label: 'System Admin',
    roleBadge: 'ADMIN',
    department: 'IT',
    email: 'admin@documind.ai',
    password: 'DocuMind@2026!',
  },
  {
    label: 'HR Manager',
    roleBadge: 'MANAGER',
    department: 'HR',
    email: 'manager.hr@documind.ai',
    password: 'DocuMind@2026!',
  },
  {
    label: 'Sales Employee',
    roleBadge: 'VIEWER',
    department: 'SALES',
    email: 'employee.sales@documind.ai',
    password: 'DocuMind@2026!',
  },
];

function isValidEmailFormat(value: string): boolean {
  const trimmed = value.trim();
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(trimmed);
}

function LoginFormContent() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const { status, login } = useAuth();

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [emailTouched, setEmailTouched] = useState(false);
  const [passwordTouched, setPasswordTouched] = useState(false);
  const [submitted, setSubmitted] = useState(false);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const redirectedRef = useRef(false);

  const showDemoAccounts = isDemoAccountsEnabled();
  const nextDestination = sanitizeNextPath(searchParams.get('next'));

  useEffect(() => {
    if (status === 'authenticated' && !redirectedRef.current) {
      redirectedRef.current = true;
      router.replace(nextDestination);
    }
  }, [nextDestination, router, status]);

  const showEmailError = (emailTouched || submitted) && !isValidEmailFormat(email);
  const showPasswordError = (passwordTouched || submitted) && password.trim().length === 0;

  async function handleSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSubmitted(true);
    setFormError(null);

    if (!isValidEmailFormat(email) || password.trim().length === 0) {
      return;
    }

    setIsSubmitting(true);
    try {
      const profile = await login({ email: email.trim(), password });
      toast.success(`Xin chào ${profile.fullName}`);
      if (!redirectedRef.current) {
        redirectedRef.current = true;
        router.replace(nextDestination);
      }
    } catch (error) {
      setFormError(formatApiErrorMessage(error));
    } finally {
      setIsSubmitting(false);
    }
  }

  function applyDemoAccount(account: DemoAccountOption) {
    setEmail(account.email);
    setPassword(account.password);
    setEmailTouched(false);
    setPasswordTouched(false);
    setSubmitted(false);
    setFormError(null);
  }

  return (
    <main className="flex min-h-screen items-center justify-center bg-slate-50 px-4 py-10">
      <Card className="w-full max-w-md border-slate-200 shadow-md">
        <CardHeader className="space-y-2 text-center">
          <div className="mx-auto flex h-11 w-11 items-center justify-center rounded-xl bg-primary/10 text-primary">
            <ShieldCheck className="h-6 w-6" aria-hidden="true" />
          </div>
          <h1 className="text-2xl font-bold leading-none tracking-tight text-slate-900">
            DocuMind AI
          </h1>
          <CardDescription>
            Đăng nhập bằng tài khoản doanh nghiệp để truy cập kho tri thức nội bộ
          </CardDescription>
        </CardHeader>

        <CardContent className="space-y-5">
          {formError ? (
            <div
              role="alert"
              className="rounded-md border border-destructive/30 bg-destructive/10 px-3.5 py-2.5 text-sm text-destructive"
            >
              {formError}
            </div>
          ) : null}

          <form onSubmit={handleSubmit} noValidate className="space-y-4">
            <div className="space-y-1.5">
              <label htmlFor="email" className="block text-sm font-medium text-slate-700">
                Email doanh nghiệp
              </label>
              <Input
                id="email"
                name="email"
                type="email"
                autoComplete="username"
                spellCheck={false}
                enterKeyHint="next"
                required
                placeholder="ten.ban@documind.ai"
                value={email}
                onChange={(event) => {
                  setEmail(event.target.value);
                  if (formError) setFormError(null);
                }}
                onBlur={() => setEmailTouched(true)}
                aria-invalid={showEmailError ? 'true' : 'false'}
                aria-describedby={showEmailError ? 'email-error' : undefined}
              />
              {showEmailError ? (
                <p id="email-error" className="text-xs font-medium text-destructive">
                  Lỗi: Vui lòng nhập địa chỉ email hợp lệ (ví dụ: name@documind.ai).
                </p>
              ) : null}
            </div>

            <div className="space-y-1.5">
              <label htmlFor="password" className="block text-sm font-medium text-slate-700">
                Mật khẩu
              </label>
              <div className="relative">
                <Input
                  id="password"
                  name="password"
                  type={showPassword ? 'text' : 'password'}
                  autoComplete="current-password"
                  enterKeyHint="done"
                  required
                  placeholder="Nhập mật khẩu"
                  className="pr-10"
                  value={password}
                  onChange={(event) => {
                    setPassword(event.target.value);
                    if (formError) setFormError(null);
                  }}
                  onBlur={() => setPasswordTouched(true)}
                  aria-invalid={showPasswordError ? 'true' : 'false'}
                  aria-describedby={showPasswordError ? 'password-error' : undefined}
                />
                <button
                  type="button"
                  onClick={() => setShowPassword((prev) => !prev)}
                  className="absolute inset-y-0 right-0 flex items-center px-3 text-slate-500 hover:text-slate-800"
                  aria-label={showPassword ? 'Ẩn mật khẩu' : 'Hiện mật khẩu'}
                >
                  {showPassword ? (
                    <EyeOff className="h-4 w-4" aria-hidden="true" />
                  ) : (
                    <Eye className="h-4 w-4" aria-hidden="true" />
                  )}
                </button>
              </div>
              {showPasswordError ? (
                <p id="password-error" className="text-xs font-medium text-destructive">
                  Lỗi: Vui lòng nhập mật khẩu.
                </p>
              ) : null}
            </div>

            <Button type="submit" className="w-full" disabled={isSubmitting}>
              <LockKeyhole className="h-4 w-4" aria-hidden="true" />
              {isSubmitting ? 'Đang đăng nhập...' : 'Đăng nhập'}
            </Button>
          </form>

          {showDemoAccounts ? (
            <section
              aria-label="Tài khoản mẫu môi trường phát triển"
              className="space-y-2.5 border-t border-slate-200 pt-4"
            >
              <div className="flex items-center gap-1.5 text-xs font-medium text-slate-500">
                <Sparkles className="h-3.5 w-3.5 text-primary" aria-hidden="true" />
                <span>Chọn nhanh tài khoản mẫu (Dev Seed)</span>
              </div>
              <div className="grid grid-cols-1 gap-2">
                {DEMO_ACCOUNTS.map((account) => (
                  <button
                    key={account.email}
                    type="button"
                    onClick={() => applyDemoAccount(account)}
                    className="flex items-center justify-between rounded-md border border-slate-200 bg-slate-50/70 px-3 py-2 text-left text-xs transition-colors hover:border-primary/40 hover:bg-primary/5"
                  >
                    <div>
                      <div className="font-medium text-slate-900">{account.label}</div>
                      <div className="text-slate-500">{account.email}</div>
                    </div>
                    <div className="flex items-center gap-1.5">
                      <Badge variant="outline">{account.department}</Badge>
                      <Badge variant="secondary">{account.roleBadge}</Badge>
                    </div>
                  </button>
                ))}
              </div>
            </section>
          ) : null}
        </CardContent>
      </Card>
    </main>
  );
}

export default function LoginPage() {
  return (
    <Suspense
      fallback={
        <main className="flex min-h-screen items-center justify-center bg-slate-50 px-4">
          <p className="text-sm text-muted-foreground">Đang tải trang đăng nhập...</p>
        </main>
      }
    >
      <LoginFormContent />
    </Suspense>
  );
}
