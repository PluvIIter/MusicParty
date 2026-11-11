package org.thornex.musicparty.dto;

/**
 * 拖动进度条请求。
 *
 * @param positionMs 目标播放位置（毫秒），服务端会裁剪到 [0, duration-500]。
 */
public record SeekRequest(long positionMs, String playbackId) {}
