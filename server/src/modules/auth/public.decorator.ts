import { SetMetadata } from '@nestjs/common';

export const IS_PUBLIC_KEY = 'wishkaro:isPublic';

/** Marks a route as reachable without an access token. Everything else requires one. */
export const Public = () => SetMetadata(IS_PUBLIC_KEY, true);
