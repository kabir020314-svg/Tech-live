import 'dotenv/config';
import { ChildProcess, spawn } from 'node:child_process';
import os from 'node:os';
import { Pool } from 'pg';

export interface CloudBroadcastJob {
  streamId: string;
  workerId: string;
  inputVideoPath: string;
  durationHours: number; // 1..168 (up to 7 days)
  loopMode: 'ONCE' | 'LOOP_2' | 'LOOP_5' | 'LOOP_10' | 'UNLIMITED';
  resolution: '360p' | '480p' | '720p' | '1080p' | '1440p' | '2160p';
  fps: 24 | 30 | 60;
  orientation: 'LANDSCAPE_16_9' | 'PORTRAIT_9_16';
  aspectMode: 'FIT' | 'CROP' | 'STRETCH' | 'BLUR_BG';
  rtmpTargets: string[];
  videoBitrateKbps: number;
  audioBitrateKbps: number;
}

const RESOLUTION_MAP: Record<string, [number, number]> = {
  '360p': [640, 360],
  '480p': [854, 480],
  '720p': [1280, 720],
  '1080p': [1920, 1080],
  '1440p': [2560, 1440],
  '2160p': [3840, 2160]
};

function buildVideoFilter(job: CloudBroadcastJob): string {
  const [baseW, baseH] = RESOLUTION_MAP[job.resolution] || [1920, 1080];
  const targetW = job.orientation === 'PORTRAIT_9_16' ? baseH : baseW;
  const targetH = job.orientation === 'PORTRAIT_9_16' ? baseW : baseH;

  switch (job.aspectMode) {
    case 'CROP':
      return `scale=${targetW}:${targetH}:force_original_aspect_ratio=increase,crop=${targetW}:${targetH},fps=${job.fps}`;
    case 'STRETCH':
      return `scale=${targetW}:${targetH},setsar=1,fps=${job.fps}`;
    case 'BLUR_BG':
      return `[0:v]split=2[bg][fg];[bg]scale=${targetW}:${targetH}:force_original_aspect_ratio=increase,crop=${targetW}:${targetH},boxblur=20:5[b];[fg]scale=${targetW}:${targetH}:force_original_aspect_ratio=decrease[f];[b][f]overlay=(W-w)/2:(H-h)/2,fps=${job.fps}`;
    case 'FIT':
    default:
      return `scale=${targetW}:${targetH}:force_original_aspect_ratio=decrease,pad=${targetW}:${targetH}:(ow-iw)/2:(oh-ih)/2:black,fps=${job.fps}`;
  }
}

export class FfmpegCloudRunner {
  private process: ChildProcess | null = null;
  private stopped = false;
  private reconnectAttempts = 0;

  constructor(private readonly pool: Pool, private readonly job: CloudBroadcastJob) {}

  async start(): Promise<void> {
    const maxSeconds = Math.min(this.job.durationHours, 168) * 3600; // Hard cap 7 continuous days
    const loopCount =
      this.job.loopMode === 'UNLIMITED'
        ? '-1'
        : this.job.loopMode === 'LOOP_2'
          ? '1'
          : this.job.loopMode === 'LOOP_5'
            ? '4'
            : this.job.loopMode === 'LOOP_10'
              ? '9'
              : '0';

    const teeMux = this.job.rtmpTargets
      .map((url) => `[f=flv:onfail=ignore]${url}`)
      .join('|');

    const args = [
      '-re',
      '-stream_loop', loopCount,
      '-i', this.job.inputVideoPath,
      '-t', String(maxSeconds),
      '-filter_complex', buildVideoFilter(this.job),
      '-c:v', 'libx264',
      '-preset', 'veryfast',
      '-tune', 'zerolatency',
      '-b:v', `${this.job.videoBitrateKbps}k`,
      '-maxrate', `${this.job.videoBitrateKbps}k`,
      '-bufsize', `${this.job.videoBitrateKbps * 2}k`,
      '-g', String(this.job.fps * 2),
      '-c:a', 'aac',
      '-b:a', `${this.job.audioBitrateKbps}k`,
      '-ar', '44100',
      '-f', 'tee',
      '-map', '0:a',
      teeMux
    ];

    this.spawnPipeline(args);
  }

  private spawnPipeline(args: string[]) {
    if (this.stopped) return;
    this.process = spawn('ffmpeg', args, { stdio: ['ignore', 'pipe', 'pipe'] });

    const heartbeat = setInterval(async () => {
      const cpu = Math.min(100, Math.round((os.loadavg()[0] / Math.max(1, os.cpus().length)) * 100));
      const ram = Math.round(((os.totalmem() - os.freemem()) / os.totalmem()) * 100);
      await this.pool.query(
        `UPDATE stream_workers SET status = 'RUNNING', cpu_percent = $1, ram_percent = $2, last_heartbeat_at = NOW() WHERE id = $3`,
        [cpu, ram, this.job.workerId]
      ).catch(() => undefined);
    }, 5000);

    this.process.on('exit', async (code) => {
      clearInterval(heartbeat);
      if (this.stopped || code === 0) {
        await this.pool.query(
          `UPDATE streams SET status = 'COMPLETED', ended_at = NOW() WHERE id = $1`,
          [this.job.streamId]
        ).catch(() => undefined);
        return;
      }

      // Exponential backoff reconnect on transient network / RTMP disconnect
      this.reconnectAttempts++;
      if (this.reconnectAttempts <= 5) {
        const delayMs = Math.min(30000, 1000 * 2 ** (this.reconnectAttempts - 1));
        await this.pool.query(
          `UPDATE streams SET status = 'RECONNECTING', reconnect_attempts = $1 WHERE id = $2`,
          [this.reconnectAttempts, this.job.streamId]
        ).catch(() => undefined);
        setTimeout(() => this.spawnPipeline(args), delayMs);
      } else {
        await this.pool.query(
          `UPDATE streams SET status = 'FAILED', ended_at = NOW() WHERE id = $1`,
          [this.job.streamId]
        ).catch(() => undefined);
      }
    });
  }

  stop() {
    this.stopped = true;
    this.process?.kill('SIGTERM');
  }
}

console.log('Tech Live Cloud FFmpeg Worker module loaded (supports 7-day continuous looping & auto-reconnect).');
