'use strict';

const path = require('node:path');

// Load `server/.env` before any module reads `process.env`. Resolved relative to
// this file so it works no matter which directory the process was started from.
// Values already present in the real environment take precedence, so deployments
// that inject config (systemd, Docker, Render, ...) are unaffected.
loadEnvFile();

const { createApp } = require('./server');
const { logger } = require('./logger');

function loadEnvFile() {
  const envPath = path.resolve(__dirname, '..', '.env');
  try {
    process.loadEnvFile(envPath);
  } catch (err) {
    // ENOENT simply means no .env file, which is normal in production where the
    // platform supplies environment variables directly.
    if (err.code !== 'ENOENT') {
      console.warn(`[musync-server] could not load ${envPath}: ${err.message}`);
    }
  }
}

const PORT = process.env.PORT ?? 3000;

const { httpServer } = createApp();

httpServer.listen(PORT, () => {
  logger.info('server listening', {
    port: Number(PORT),
    logLevel: logger.level,
    node: process.version,
    youtubeApiKey: process.env.YOUTUBE_API_KEY ? 'configured' : 'missing',
  });
});

process.on('unhandledRejection', (reason) => {
  logger.error('unhandled promise rejection', { reason });
});

process.on('uncaughtException', (err) => {
  logger.error('uncaught exception', { err });
});
