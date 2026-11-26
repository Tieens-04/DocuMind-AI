import type { Metadata } from 'next';
import { Toaster } from '@/components/ui/sonner';
import { AuthProvider } from '@/providers/auth-provider';
import './globals.css';

export const metadata: Metadata = {
  title: 'DocuMind AI — Trợ lý Tri thức Doanh nghiệp',
  description: 'Hệ thống hỏi đáp tài liệu nội bộ tuân thủ phân quyền RBAC',
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="vi">
      <body className="min-h-screen bg-background font-sans text-foreground antialiased">
        <AuthProvider>
          {children}
          <Toaster />
        </AuthProvider>
      </body>
    </html>
  );
}
