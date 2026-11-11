import { ref, computed, watch, onScopeDispose } from 'vue';
import { useUserStore } from '../stores/user.js';
import { useSourcesStore } from '../stores/sources.js';
import { musicApi } from '../api/music.js';
import { useToast } from './useToast';
import { createRequestScope, isCanceled } from '../domain/sources';

export function usePlaylistLogic(platform, songs, listMode, loading) {
    const userStore = useUserStore();
    const sources = useSourcesStore();
    const { error } = useToast();
    const bindings = computed(() => userStore.bindings);
    const categories = computed(() => sources.descriptor(platform.value)?.collectionCategories || []);
    const categoryId = ref(categories.value[0]?.id || '');
    const category = computed(() => categories.value.find((item) => item.id === categoryId.value));
    const playlists = ref([]);
    const currentContainer = ref(null);
    const currentPlaylistId = computed(() => currentContainer.value?.id);
    const searchUserKeyword = ref('');
    const userSearchResults = ref([]);
    const isSearchingUser = ref(false);
    const isPlaylistsLoading = ref(false);
    const isLoadingMore = ref(false);
    const nextCursor = ref(null);
    const collectionCursor = ref(null);
    const hasMore = computed(() => nextCursor.value != null);
    const collectionRequests = createRequestScope();
    const trackRequests = createRequestScope();
    const userRequests = createRequestScope();
    const report = (e, request) => {
        if (request.current() && !isCanceled(e)) error(e.response?.data?.message || e.message || '读取失败');
    };
    const cancel = () => {
        collectionRequests.cancel();
        trackRequests.cancel();
        userRequests.cancel();
        isPlaylistsLoading.value = false;
        isLoadingMore.value = false;
        isSearchingUser.value = false;
        loading.value = false;
    };
    const fetchCollections = async (append = false) => {
        if (
            !category.value ||
            !bindings.value[platform.value] ||
            !sources.supports(platform.value, 'personalCollections')
        )
            return;
        const selected = category.value;
        if (selected.entryKind === 'TRACK') listMode.value = 'collection';
        const request = collectionRequests.start();
        isPlaylistsLoading.value = true;
        try {
            const page = await musicApi.getCollections(
                platform.value,
                selected.id,
                bindings.value[platform.value],
                append ? collectionCursor.value : null,
                request.signal
            );
            if (!request.current()) return;
            collectionCursor.value = page.nextCursor;
            if (selected.entryKind === 'TRACK') {
                listMode.value = 'collection';
                songs.value = append ? [...songs.value, ...page.items] : page.items;
            } else playlists.value = append ? [...playlists.value, ...page.items] : page.items;
        } catch (e) {
            report(e, request);
        } finally {
            if (request.current()) isPlaylistsLoading.value = false;
        }
    };
    const searchUser = async () => {
        const q = searchUserKeyword.value.trim();
        if (!q || !sources.supports(platform.value, 'userSearch')) return;
        const request = userRequests.start();
        isSearchingUser.value = true;
        userSearchResults.value = [];
        try {
            const page = await musicApi.searchUser(platform.value, q, request.signal);
            if (request.current()) userSearchResults.value = page.items;
        } catch (e) {
            report(e, request);
        } finally {
            if (request.current()) isSearchingUser.value = false;
        }
    };
    const bindUser = (user) => {
        const id = String(user.id ?? '').trim();
        if (!id) return;
        userStore.updateBinding(platform.value, id);
        userSearchResults.value = [];
        searchUserKeyword.value = '';
    };
    const fetchTracks = async (append = false) => {
        if (!currentContainer.value) return;
        const request = trackRequests.start();
        const container = currentContainer.value;
        if (append) isLoadingMore.value = true;
        else loading.value = true;
        try {
            const page = await musicApi.getContainerTracks(
                platform.value,
                container.collectionType,
                container.id,
                append ? nextCursor.value : null,
                request.signal
            );
            if (!request.current()) return;
            songs.value = append ? [...songs.value, ...page.items] : page.items;
            nextCursor.value = page.nextCursor;
        } catch (e) {
            report(e, request);
        } finally {
            if (request.current()) {
                loading.value = false;
                isLoadingMore.value = false;
            }
        }
    };
    const loadPlaylist = async (container) => {
        listMode.value = 'playlist';
        currentContainer.value = container;
        songs.value = [];
        nextCursor.value = null;
        await fetchTracks();
    };
    const handleScroll = (event) => {
        const el = event.target;
        if (
            listMode.value === 'playlist' &&
            el.scrollHeight - el.scrollTop - el.clientHeight < 100 &&
            hasMore.value &&
            !loading.value &&
            !isLoadingMore.value
        )
            fetchTracks(true);
    };
    watch(
        listMode,
        () => {
            trackRequests.cancel();
            collectionRequests.cancel();
            isPlaylistsLoading.value = false;
            isLoadingMore.value = false;
            loading.value = false;
            if (listMode.value !== 'playlist') currentContainer.value = null;
        },
        { flush: 'sync' }
    );
    watch(
        platform,
        () => {
            categoryId.value = categories.value[0]?.id || '';
        },
        { flush: 'sync' }
    );
    watch(categories, () => {
        if (!categories.value.some((item) => item.id === categoryId.value))
            categoryId.value = categories.value[0]?.id || '';
    });
    watch(
        () => [platform.value, bindings.value[platform.value], categoryId.value],
        () => {
            cancel();
            currentContainer.value = null;
            songs.value = [];
            playlists.value = [];
            userSearchResults.value = [];
            collectionCursor.value = null;
            nextCursor.value = null;
            fetchCollections();
        },
        { immediate: true, flush: 'sync' }
    );
    onScopeDispose(cancel);
    return {
        playlists,
        categories,
        categoryId,
        category,
        currentContainer,
        currentPlaylistId,
        searchUserKeyword,
        userSearchResults,
        isSearchingUser,
        isPlaylistsLoading,
        hasMore,
        isLoadingMore,
        bindings,
        searchUser,
        bindUser,
        loadPlaylist,
        fetchPlaylists: fetchCollections,
        handleScroll,
        collectionCursor,
        cancel,
    };
}
