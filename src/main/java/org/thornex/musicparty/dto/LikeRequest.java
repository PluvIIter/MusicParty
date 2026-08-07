package org.thornex.musicparty.dto;

/**
 * 点赞请求。
 * position 为客户端上报的当前播放位置（毫秒，音频实际听到的位置），
 * 服务器用它作为播放条上的点赞打点，可空——为空时回退到服务器自身计算的进度。
 */
public record LikeRequest(Long position) {}
