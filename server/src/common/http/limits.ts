/**
 * The maximum JSON request body the API accepts.
 *
 * This is NOT a cap being raised. Until M3 the server configured no limit at all, which
 * meant it inherited body-parser's default of 100 KB — a limit nothing in this repo
 * documents, sitting BELOW the one that is documented.
 *
 * `SyncPushSchema` caps each array at 500 rows. The largest a `PersonUpsertSchema` row can
 * legally be is about 2.5 KB (`notes` alone allows 2,000 characters), so 500 rows is roughly
 * 1.25 MB. At 100 KB the body limit bound first — at roughly 350-500 people — so a client
 * that respected the documented cap was still rejected, and rejected by the one failure that
 * carried no diagnosis: a `PayloadTooLargeError` is not a Nest `HttpException`, so it
 * surfaced as a bare 500 with no mention of size anywhere.
 *
 * 2 MB puts the row cap back in charge: a batch that is too large now fails validation and
 * says which array, instead of failing transport and saying nothing. See docs/M3_PLAN.md §1.
 *
 * It lives in its own module because both `configureApp` and `AllExceptionsFilter` need it,
 * and the filter must not import the thing that installs the filter.
 */
export const JSON_BODY_LIMIT = '2mb';
