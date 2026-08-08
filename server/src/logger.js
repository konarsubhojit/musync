'use strict';

/**
 * Minimal dependency-free structured logger.
 *
 * Levels are ordered `error < warn < info < debug < trace`. The active level is
 * read from `LOG_LEVEL` (default `info`; `debug` when `NODE_ENV=development`),
 * so verbose tracing can be switched on without a code change:
 *
 *   LOG_LEVEL=trace npm start
 *
 * Output is single-line so it stays greppable and works with any log collector.
 * Set `LOG_FORMAT=json` to emit JSON objects instead of human-readable text.
 */

const LEVELS = { error: 0, warn: 1, info: 2, debug: 3, trace: 4 };

const DEFAULT_LEVEL = process.env.NODE_ENV === 'development' ? 'debug' : 'info';

function resolveLevel() {
  const configured = (process.env.LOG_LEVEL ?? DEFAULT_LEVEL).toLowerCase();
  return configured in LEVELS ? configured : DEFAULT_LEVEL;
}

let activeLevel = resolveLevel();
const asJson = (process.env.LOG_FORMAT ?? '').toLowerCase() === 'json';

/** Values that must never reach the logs, matched case-insensitively by key. */
const REDACTED_KEYS = /^(api_?key|authorization|token|secret|password)$/i;

/**
 * Serialises a context object, dropping undefined values, redacting anything
 * that looks like a credential and flattening Errors to `message` + `stack`.
 *
 * @param {Record<string, unknown>} context
 * @returns {Record<string, unknown>}
 */
function sanitise(context) {
  const output = {};
  for (const [key, value] of Object.entries(context)) {
    if (value === undefined) continue;
    if (REDACTED_KEYS.test(key)) {
      output[key] = '[redacted]';
    } else if (value instanceof Error) {
      output[key] = value.message;
      output[`${key}Stack`] = value.stack;
    } else {
      output[key] = value;
    }
  }
  return output;
}

/**
 * @param {string} level
 * @param {string} message
 * @param {Record<string, unknown>} context
 */
function emit(level, message, context) {
  if (LEVELS[level] > LEVELS[activeLevel]) return;

  const fields = sanitise(context);
  const timestamp = new Date().toISOString();

  if (asJson) {
    console.log(JSON.stringify({ timestamp, level, message, ...fields }));
    return;
  }

  const suffix = Object.entries(fields)
    .map(([key, value]) => `${key}=${typeof value === 'string' ? value : JSON.stringify(value)}`)
    .join(' ');
  const level5 = level.toUpperCase().padEnd(5);
  const contextSuffix = suffix === '' ? '' : ` ${suffix}`;
  const line = `${timestamp} ${level5} [musync-server] ${message}${contextSuffix}`;

  if (level === 'error') console.error(line);
  else if (level === 'warn') console.warn(line);
  else console.log(line);
}

const logger = {
  error: (message, context = {}) => emit('error', message, context),
  warn: (message, context = {}) => emit('warn', message, context),
  info: (message, context = {}) => emit('info', message, context),
  debug: (message, context = {}) => emit('debug', message, context),
  /** High-volume per-event tracing (heartbeats, position updates). */
  trace: (message, context = {}) => emit('trace', message, context),

  /** Current active level, e.g. for a startup banner. */
  get level() {
    return activeLevel;
  },

  /** Overrides the active level. Intended for tests and admin tooling. */
  setLevel(level) {
    const next = String(level).toLowerCase();
    if (next in LEVELS) activeLevel = next;
    return activeLevel;
  },
};

module.exports = { logger, LEVELS };
