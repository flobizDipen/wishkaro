import { Body, Controller, HttpCode, HttpStatus, Post } from '@nestjs/common';
import { z } from 'zod';
import { ZodValidationPipe } from '../../common/zod/zod-validation.pipe';
import { CurrentUserId } from '../auth/current-user.decorator';
import { DevicesService, type RegisterDeviceResult } from './devices.service';

const RegisterDeviceSchema = z.object({
  fcmToken: z.string().min(10),
  platform: z.enum(['ANDROID', 'IOS']),
  /** IANA zone from the device. A change here reschedules pending reminders (FR-EVT-08). */
  timezone: z.string().min(1).optional(),
});

@Controller('v1/devices')
export class DevicesController {
  constructor(private readonly devices: DevicesService) {}

  @Post()
  @HttpCode(HttpStatus.OK)
  register(
    @CurrentUserId() userId: string,
    @Body(new ZodValidationPipe(RegisterDeviceSchema)) body: z.infer<typeof RegisterDeviceSchema>,
  ): Promise<RegisterDeviceResult> {
    return this.devices.register(userId, body);
  }
}
