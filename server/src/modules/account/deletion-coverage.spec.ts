import { Prisma } from '@prisma/client';
import {
  DELETION_EXEMPT,
  DELETION_INVENTORY,
  DELETION_NEVER_TOUCHES,
} from './deletion-inventory';

/**
 * **The guard against the failure that has not happened yet.**
 *
 * `account-deletion.db.spec.ts` proves the deletion removes what is in the inventory today.
 * It cannot prove the inventory is complete, and it never will — a table added in M7 for
 * subscriptions, in M8 for gift click-outs or in M10 for video renders would simply not be
 * asserted on, every suite would stay green, and the leak would be found by a user.
 *
 * So this reads **Prisma's own DMMF** — the schema as the client sees it — finds every model
 * carrying a `userId` scalar, and fails if it is not named in `DELETION_INVENTORY`. Adding a
 * user-owned table without deciding what deletion does with it is now a build failure.
 *
 * `MILESTONE_CHECKLIST.md`, bought by M6 shipping the same `${'$'}` over-escaping twice more
 * after it was already on the list: *"the checklist is a review aid, not a guard. Where a
 * mistake has a mechanical signature, write the grep or the test instead."* This is the grep.
 */

/** Every model in the schema that has a `userId` scalar field. */
function modelsOwnedByAUser(): { model: string; nullable: boolean }[] {
  return Prisma.dmmf.datamodel.models
    .map((model) => {
      const field = model.fields.find((f) => f.name === 'userId' && f.kind === 'scalar');
      return field ? { model: model.name, nullable: !field.isRequired } : null;
    })
    .filter((m): m is { model: string; nullable: boolean } => m !== null);
}

describe('the deletion inventory covers the schema', () => {
  it('reads a real schema, not an empty one', () => {
    // Without this, a DMMF that failed to load would make every test below pass by iterating
    // nothing — the vacuous-green shape M5 shipped twice and the checklist now names.
    expect(Prisma.dmmf.datamodel.models.length).toBeGreaterThan(10);
    expect(modelsOwnedByAUser().length).toBeGreaterThan(5);
  });

  it('names every model that carries a userId', () => {
    const named = new Set([
      ...DELETION_INVENTORY.map((e) => e.model),
      ...DELETION_EXEMPT.map((e) => e.model),
    ]);

    const missing = modelsOwnedByAUser()
      .map((m) => m.model)
      .filter((model) => !named.has(model));

    expect(missing).toEqual([]);
  });

  it('names no model that does not exist', () => {
    // The other direction. A table renamed or dropped leaves an entry describing nothing, and
    // an inventory with fiction in it is one nobody trusts the rest of.
    const real = new Set(Prisma.dmmf.datamodel.models.map((m) => m.name));

    for (const entry of DELETION_INVENTORY) {
      expect(real.has(entry.model)).toBe(true);
    }
  });

  /**
   * The disposition in the inventory has to match what the DATABASE will actually do, because
   * the database is what enforces it. A table marked ANONYMISE whose `user_id` is still NOT
   * NULL cannot be anonymised at all — the update would throw and, worse, an `ON DELETE
   * CASCADE` underneath it would already have taken the rows.
   */
  it('marks a table ANONYMISE only where the column can actually hold a null', () => {
    const nullable = new Map(modelsOwnedByAUser().map((m) => [m.model, m.nullable]));

    for (const entry of DELETION_INVENTORY) {
      if (entry.disposition === 'ANONYMISE') {
        expect(nullable.get(entry.model)).toBe(true);
      }
      if (entry.disposition === 'CASCADE' && entry.model !== 'User') {
        // The converse: a cascading table whose column became nullable would silently start
        // keeping rows with a null owner instead of removing them — the same outcome as
        // ANONYMISE, reached by accident and recorded nowhere.
        expect(nullable.get(entry.model)).toBe(false);
      }
    }
  });

  it('the three anonymised tables are exactly the ones Dipen decided on', () => {
    // Pinned by name rather than counted, because this is a product decision with a financial
    // consequence (§10 rule 2) and a privacy one (DPDP erasure). Widening it later should be a
    // visible edit here, not a quiet addition somewhere else.
    expect(DELETION_INVENTORY.filter((e) => e.disposition === 'ANONYMISE').map((e) => e.model)).toEqual([
      'Generation',
      'AiUsage',
      'ContentReport',
    ]);
  });

  it('leaves theme_cache alone, and theme_cache has no owner to begin with', () => {
    // §10 rule 5 and docs/M6_PLAN.md §3.6: shared product art, person-independent by design.
    // Asserted on both sides — it is excluded by intent AND it has no `userId` for the guard
    // above to catch, so neither fact is doing the work alone.
    expect(DELETION_NEVER_TOUCHES).toContain('theme_cache');
    expect(modelsOwnedByAUser().map((m) => m.model)).not.toContain('ThemeCache');
  });

  it('every exemption carries a reason', () => {
    for (const exemption of DELETION_EXEMPT) {
      expect(exemption.why.trim().length).toBeGreaterThan(20);
    }
  });
});
