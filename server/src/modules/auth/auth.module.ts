import { Global, Module } from '@nestjs/common';
import { JwtModule } from '@nestjs/jwt';
import { APP_ENV, type AppEnv } from '../../config/env';
import { AuthController, DevAuthController } from './auth.controller';
import { AuthService, GOOGLE_VERIFIER, GoogleOAuthVerifier } from './auth.service';

@Global()
@Module({
  imports: [JwtModule.register({})],
  controllers: [AuthController, DevAuthController],
  providers: [
    AuthService,
    {
      provide: GOOGLE_VERIFIER,
      inject: [APP_ENV],
      useFactory: (env: AppEnv) => new GoogleOAuthVerifier(env.GOOGLE_CLIENT_ID),
    },
  ],
  exports: [AuthService],
})
export class AuthModule {}
