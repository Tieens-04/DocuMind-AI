'use client';

import React from 'react';
import { FileText } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';

export default function DocumentsPage() {
  return (
    <div className="mx-auto max-w-5xl space-y-6">
      <div className="flex items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-slate-900">Kho Tài liệu</h1>
          <p className="text-sm text-muted-foreground">
            Quản lý tải lên, phân quyền và theo dõi tiến trình nạp vector tài liệu phòng ban
          </p>
        </div>
        <Badge variant="secondary">DA-23 Ready</Badge>
      </div>

      <Tabs defaultValue="all">
        <TabsList>
          <TabsTrigger value="all">Tất cả tài liệu</TabsTrigger>
          <TabsTrigger value="upload">Tải lên tài liệu</TabsTrigger>
        </TabsList>
        <TabsContent value="all">
          <Card>
            <CardHeader>
              <div className="flex items-center gap-2 text-primary">
                <FileText className="h-5 w-5" aria-hidden="true" />
                <CardTitle className="text-lg">Danh sách tài liệu</CardTitle>
              </div>
              <CardDescription>
                Khung quản lý tài liệu dành cho Trưởng bộ phận và Quản trị viên (chi tiết bảng &
                polling triển khai ở DA-23).
              </CardDescription>
            </CardHeader>
            <CardContent>
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>Tên tài liệu</TableHead>
                    <TableHead>Phạm vi</TableHead>
                    <TableHead>Trạng thái</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  <TableRow>
                    <TableCell colSpan={3} className="text-center text-muted-foreground">
                      Dữ liệu kho tài liệu sẽ được nối API ở ticket DA-23.
                    </TableCell>
                  </TableRow>
                </TableBody>
              </Table>
            </CardContent>
          </Card>
        </TabsContent>
        <TabsContent value="upload">
          <Card>
            <CardHeader>
              <CardTitle className="text-lg">Kéo thả & Tải lên (DA-23)</CardTitle>
              <CardDescription>
                Hỗ trợ .pdf, .docx, .txt, .md tối đa 50MB với mức quyền DEPARTMENT hoặc PUBLIC.
              </CardDescription>
            </CardHeader>
          </Card>
        </TabsContent>
      </Tabs>
    </div>
  );
}
