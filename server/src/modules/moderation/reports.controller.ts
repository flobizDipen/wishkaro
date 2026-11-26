import { Body, Controller, HttpCode, HttpStatus, Post } from '@nestjs/common';
import { ZodValidationPipe } from '../../common/zod/zod-validation.pipe';
import { CurrentUserId } from '../auth/current-user.decorator';
import { ReportsService } from './reports.service';
import { ReportContentSchema, type ReportContentRequest } from './reports.schemas';

/** §9's `POST /v1/ai/report`, listed since v1 and written to for the first time in M6. */
@Controller('v1/ai')
export class ReportsController {
  constructor(private readonly reports: ReportsService) {}

  @Post('report')
  @HttpCode(HttpStatus.ACCEPTED)
  report(
    @CurrentUserId() userId: string,
    @Body(new ZodValidationPipe(ReportContentSchema)) body: ReportContentRequest,
  ): Promise<{ id: string }> {
    return this.reports.report(userId, body);
  }
}
