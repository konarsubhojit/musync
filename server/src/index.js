'use strict';

const { createApp } = require('./server');
const { logger } = require('./logger');

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
