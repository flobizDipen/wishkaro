import { Global, Module } from '@nestjs/common';
import { PersonDerivationService } from './person-derivation.service';

/**
 * Global on purpose. People, events and sync all write rows that change a person's derived
 * values, and all three must be able to finish a write by recomputing them. Wiring it into
 * each module separately would let a fourth write path appear later without one.
 */
@Global()
@Module({
  providers: [PersonDerivationService],
  exports: [PersonDerivationService],
})
export class DerivationModule {}
