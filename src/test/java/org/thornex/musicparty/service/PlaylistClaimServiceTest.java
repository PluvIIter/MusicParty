package org.thornex.musicparty.service;

import org.junit.jupiter.api.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.*;
import org.thornex.musicparty.enums.*;
import org.thornex.musicparty.service.api.NeteaseMusicApiService;
import org.thornex.musicparty.service.stream.LiveStreamService;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class PlaylistClaimServiceTest {
    private AppProperties properties;
    private UserService users;
    private MusicQueueManager queue;
    private MusicPlayerService player;
    private QueuePersistenceService persistence;
    private PlaylistClaimService claims;
    private User target, source, other;

    @BeforeEach void setUp() {
        properties=new AppProperties();properties.getQueue().setAllowPlaylistClaim(true);
        var events=mock(ApplicationEventPublisher.class);
        users=new UserService(events);queue=new MusicQueueManager(properties);
        player=new MusicPlayerService(List.of(),users,mock(LocalCacheService.class),mock(LiveStreamService.class),queue,events,properties,mock(NeteaseMusicApiService.class),mock(PrivateDjService.class));
        persistence=mock(QueuePersistenceService.class);
        claims=new PlaylistClaimService(users,queue,player,persistence,properties);
        target=users.handleConnect("target-session","target","游客");
        source=users.handleConnect("old-session","source","Original");
        source.getBindings().put("netease","private-old-account");
        source.setSessionId(null);source.setLastActiveTime(System.currentTimeMillis()-60_000);
        other=users.handleConnect("other-session","other","Other");
        target.getBindings().put("netease","current-account");
        queue.restore(List.of(item("a","source","Original",Priority.GLOBAL_TOP),item("mine","target","游客",Priority.REGULAR),item("other","other","Other",Priority.USER_TOP),item("b","source","Original",Priority.USER_TOP)),List.of(music("historic")));
    }
    @AfterEach void stopScheduler(){((ScheduledExecutorService)ReflectionTestUtils.getField(users,"scheduler")).shutdownNow();}
    private static Music music(String id){return new Music(id,"Track "+id,List.of("Fixture"),180000,"netease","");}
    private static MusicQueueItem item(String id,String token,String name,Priority priority){return new MusicQueueItem(id,music(id),new UserSummary(token,"old",name,false),QueueItemStatus.READY,priority);}
    @SuppressWarnings("unchecked") private <T> AtomicReference<T> reference(String field){return (AtomicReference<T>)ReflectionTestUtils.getField(player,field);}
    private void reject(String session,String token){var before=queue.getQueueSnapshot();String name=target.getName();assertThrows(IllegalArgumentException.class,()->claims.claim(session,token));assertEquals(before,queue.getQueueSnapshot());assertEquals(name,target.getName());}

    @Test void disabledByDefaultAndRejectsDirectCommands() {
        assertFalse(new AppProperties().getQueue().isAllowPlaylistClaim());
        properties.getQueue().setAllowPlaylistClaim(false);
        reject("target-session","source");verifyNoInteractions(persistence);
    }
    @Test void rejectsForgedAndSupersededSessions() {
        reject("forged-session","source");
        users.handleConnect("replacement-session","target","ignored");
        reject("target-session","source");verifyNoInteractions(persistence);
    }
    @Test void protectsOnlineAndRecentlyDisconnectedOwners() {
        source.setSessionId("old-session");reject("target-session","source");
        source.setSessionId(null);source.setLastActiveTime(System.currentTimeMillis());reject("target-session","source");
        source.setLastActiveTime(0);
        users.handleConnect("old-session","source","Original");
        users.disconnectUser("old-session");source.setLastActiveTime(0);
        reject("target-session","source");verifyNoInteractions(persistence);
    }
    @Test void rejectsOwnMissingVirtualAndGuestPlaylists() {
        reject("target-session","target");reject("target-session","absent");reject("target-session","SYSTEM");reject("target-session","ADMIN");reject("target-session","__FM__");
        queue.restore(List.of(new MusicQueueItem("guest",music("guest"),new UserSummary("guest","old","Former",true),QueueItemStatus.READY)),List.of());
        reject("target-session","guest");verifyNoInteractions(persistence);
    }
    @Test void exactNameCollisionRejectsWithoutRenamingAnyone() {
        other.setName("oRiGiNaL");reject("target-session","source");assertEquals("oRiGiNaL",other.getName());verifyNoInteractions(persistence);
    }
    @Test void absentOwnerCanBeClaimedWithoutItsIdentityFile() throws Exception {
        source.setLastActiveTime(0);users.cleanupExpiredUsers();assertTrue(users.getUserByToken("source").isEmpty());
        assertEquals(2,claims.claim("target-session","source").claimed());assertEquals("Original",target.getName());
    }
    @Test void mergesAllSongsAndPromotesGuestWithoutCopyingCredentials() throws Exception {
        var before=queue.getQueueSnapshot();var history=queue.getHistorySnapshot();
        var result=claims.claim("target-session","source");var after=queue.getQueueSnapshot();
        assertEquals(2,result.claimed());assertEquals("target",result.member().token());assertEquals("Original",target.getName());assertFalse(target.isGuest());
        assertEquals(Map.of("netease","current-account"),target.getBindings());assertTrue(users.getUserByToken("source").isEmpty());
        assertEquals(before.size(),after.size());assertEquals(history,queue.getHistorySnapshot());
        for(int i=0;i<before.size();i++){
            var old=before.get(i);var now=after.get(i);
            assertEquals(old.queueId(),now.queueId());assertEquals(old.music(),now.music());assertEquals(old.status(),now.status());assertEquals(old.priority(),now.priority());
            if(old.enqueuedBy().token().equals("other"))assertEquals(old,now);else assertEquals(result.member(),now.enqueuedBy());
        }
        verify(persistence).saveQueueSnapshot(after);
        reject("target-session","source");
        var returning=users.handleConnect("return-session","source","Original");assertNotEquals("Original",returning.getName());
    }
    @Test void savesBeforeMutatingRuntimeAndRollsBackOnSaveFailure() throws Exception {
        var before=queue.getQueueSnapshot();
        doAnswer(invocation->{assertEquals(before,queue.getQueueSnapshot());assertTrue(target.isGuest());assertSame(source,users.getUserByToken("source").orElseThrow());throw new IOException("fixture disk failure");}).when(persistence).saveQueueSnapshot(anyList());
        assertThrows(IOException.class,()->claims.claim("target-session","source"));assertEquals(before,queue.getQueueSnapshot());assertTrue(target.isGuest());assertEquals("游客",target.getName());
    }
    @Test void preservesPlayingAudioPausePositionAndFairMemberReference() throws Exception {
        var playing=new PlayableMusic("a","Track",List.of("Fixture"),180000,"netease","fixture://audio","",false);
        reference("currentMusic").set(playing);reference("currentEnqueuerId").set("source");reference("currentEnqueuerName").set("Original");
        ((AtomicLong)ReflectionTestUtils.getField(player,"positionAnchor")).set(12345);
        ((AtomicBoolean)ReflectionTestUtils.getField(player,"isPaused")).set(true);
        ((AtomicLong)ReflectionTestUtils.getField(player,"playHeadVersion")).set(7);
        @SuppressWarnings("unchecked") var last=(AtomicReference<String>)ReflectionTestUtils.getField(queue,"lastPlayedUserToken");last.set("source");
        claims.claim("target-session","source");
        assertSame(playing,reference("currentMusic").get());assertEquals("target",reference("currentEnqueuerId").get());assertEquals("Original",reference("currentEnqueuerName").get());assertEquals("target",last.get());
        assertEquals(12345,((AtomicLong)ReflectionTestUtils.getField(player,"positionAnchor")).get());assertTrue(((AtomicBoolean)ReflectionTestUtils.getField(player,"isPaused")).get());assertEquals(7,((AtomicLong)ReflectionTestUtils.getField(player,"playHeadVersion")).get());
    }
    @Test void pendingConsumedEntryUsesClaimedOwnerButFutureRequestsDoNot() throws Exception {
        var pending=item("pending","source","Original",Priority.REGULAR);
        var playable=new PlayableMusic("pending","Track",List.of("Fixture"),180000,"netease","fixture://audio","",false);
        claims.claim("target-session","source");
        ReflectionTestUtils.invokeMethod(player,"applyNewSong",playable,pending);assertEquals("target",reference("currentEnqueuerId").get());
        ((AtomicLong)ReflectionTestUtils.getField(player,"playHeadVersion")).incrementAndGet();
        ReflectionTestUtils.invokeMethod(player,"applyNewSong",playable,pending);assertEquals("source",reference("currentEnqueuerId").get());
    }
    @Test void concurrentClaimantsCommitExactlyOnce() throws Exception {
        users.handleConnect("rival-session","rival","Rival");
        var executor=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try{
            var first=executor.submit(()->{start.await();return attempt("target-session");});
            var second=executor.submit(()->{start.await();return attempt("rival-session");});start.countDown();
            assertNotEquals(first.get(5,TimeUnit.SECONDS),second.get(5,TimeUnit.SECONDS));
            assertEquals(4,queue.getQueueSnapshot().size());verify(persistence,times(1)).saveQueueSnapshot(anyList());
        }finally{executor.shutdownNow();}
    }
    @Test void reconnectRaceNeverPartiallyTransfers() throws Exception {
        var executor=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try{
            var claim=executor.submit(()->{start.await();return attempt("target-session");});
            var reconnect=executor.submit(()->{start.await();return users.handleConnect("returned","source","Original");});start.countDown();
            boolean won=claim.get(5,TimeUnit.SECONDS);reconnect.get(5,TimeUnit.SECONDS);
            assertEquals(won?0:2,queue.getQueueSnapshot().stream().filter(i->i.enqueuedBy().token().equals("source")).count());
        }finally{executor.shutdownNow();}
    }
    private boolean attempt(String session)throws IOException{try{claims.claim(session,"source");return true;}catch(IllegalArgumentException error){return false;}}
}
