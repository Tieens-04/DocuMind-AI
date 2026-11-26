import React from 'react';
import { BarChart3 } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import {
  Card,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card';

export default function AdminAnalyticsPage() {
  return (
    <div className="mx-auto max-w-5xl space-y-6">
      <div className="flex items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-slate-900">
            FinOps & Audit Logs
          </h1>
          <p className="text-sm text-muted-foreground">
            Theo dõi chi phí Token, tỷ lệ Redis Cache Hit và nhật ký truy vấn toàn hệ thống
          </p>
        </div>
        <Badge variant="secondary">SYSTEM_ADMIN Only</Badge>
      </div>

      <Card>
        <CardHeader>
          <div className="flex items-center gap-2 text-primary">
            <BarChart3 className="h-5 w-5" aria-hidden="true" />
            <CardTitle className="text-lg">Bảng điều khiển FinOps (DA-24)</CardTitle>
          </div>
          <CardDescription>
            Route được bảo vệ độc quyền cho vai trò SYSTEM_ADMIN; các biểu đồ thống kê và bảng
            Audit Logs sẽ được hoàn thiện trong DA-24.
          </CardDescription>
        </CardHeader>
      </Card>
    </div>
  );
}
