package org.thornex.musicparty.controller;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.thornex.musicparty.dto.UserSummary;
import org.thornex.musicparty.service.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlaylistClaimControllerTest {
    @Test void repliesOnlyToActualSessionAndPublishesUpdatedIdentity() throws Exception {
        var service=mock(PlaylistClaimService.class);var player=mock(MusicPlayerService.class);var messages=mock(SimpMessagingTemplate.class);
        var member=new UserSummary("current-token","actual-session","Recovered",false);
        when(service.claim("actual-session","old-token")).thenReturn(new PlaylistClaimService.Claimed(4,member));
        new PlaylistClaimController(service,player,messages).claim(new PlaylistClaimController.ClaimRequest("request","old-token"),"actual-session");
        verify(messages).convertAndSendToUser(eq("actual-session"),eq("/queue/me"),eq(member),argThat((MessageHeaders h)->"actual-session".equals(SimpMessageHeaderAccessor.getSessionId(h))));
        verify(messages).convertAndSendToUser(eq("actual-session"),eq("/queue/playlist/claim"),argThat((Object o)->o instanceof PlaylistClaimController.ClaimResult result&&!result.error()&&result.claimed()==4&&result.member().equals(member)),any(MessageHeaders.class));
        verify(player).broadcastQueueUpdate();verify(player).broadcastFullPlayerState();
    }
    @Test void rejectionSendsPrivateErrorWithoutBroadcast() throws Exception {
        var service=mock(PlaylistClaimService.class);var player=mock(MusicPlayerService.class);var messages=mock(SimpMessagingTemplate.class);
        when(service.claim(anyString(),anyString())).thenThrow(new IllegalArgumentException("管理员已关闭歌单认领"));
        new PlaylistClaimController(service,player,messages).claim(new PlaylistClaimController.ClaimRequest("request","old"),"session");
        verify(messages).convertAndSendToUser(eq("session"),eq("/queue/playlist/claim"),argThat((Object o)->o instanceof PlaylistClaimController.ClaimResult result&&result.error()&&result.claimed()==0),any(MessageHeaders.class));verifyNoInteractions(player);
    }
}
