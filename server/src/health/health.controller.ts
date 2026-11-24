import { Controller, Get } from '@nestjs/common';
import { PrismaService } from '../prisma/prisma.service';
import { Public } from '../modules/auth/public.decorator';

@Controller('health')
export class HealthController {
  constructor(private readonly prisma: PrismaService) {}

  /**
   * Liveness plus a real database round-trip, so a half-dead server reports itself as such.
   * Public: container healthchecks and Railway's prober have no session.
   */
  @Public()
  @Get()
  async health(): Promise<{ status: string; db: string; uptimeSeconds: number }> {
    let db = 'up';
    try {
      await this.prisma.$queryRaw`SELECT 1`;
    } catch {
      db = 'down';
    }
    return {
      status: db === 'up' ? 'ok' : 'degraded',
      db,
      uptimeSeconds: Math.round(process.uptime()),
    };
  }
}
