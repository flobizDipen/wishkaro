import { Module } from '@nestjs/common';
import { AccountController } from './account.controller';
import { ExportService } from './export.service';

/**
 * FR-SET-03 (export) and, once decision 3 in `docs/M9_PLAN.md` is answered, FR-SET-04
 * (account deletion). §9 has listed both endpoints since v1 and neither existed.
 *
 * No imports: `PrismaModule` is global and `MediaModule` is `@Global` for the blob store.
 */
@Module({
  controllers: [AccountController],
  providers: [ExportService],
  exports: [ExportService],
})
export class AccountModule {}
