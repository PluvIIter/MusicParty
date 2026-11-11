import client from './client';
import { STORAGE_KEYS } from '../constants/storage';
const segment = (value) => encodeURIComponent(value);
const sourcePath = (source) => `/api/v2/sources/${segment(source)}`;
const options = (signal, params) => ({
    signal,
    params,
    timeout: 20000,
    headers: { 'X-Room-Password': localStorage.getItem(STORAGE_KEYS.ROOM_PASSWORD) || '' },
});

export const musicApi = {
    getSources: (signal) => client.get('/api/v2/sources', options(signal)),
    search: (source, q, signal) => client.get(`${sourcePath(source)}/tracks/search`, options(signal, { q })),
    searchUser: (source, q, signal) => client.get(`${sourcePath(source)}/users/search`, options(signal, { q })),
    getCategories: (source, signal) => client.get(`${sourcePath(source)}/collection-categories`, options(signal)),
    getCollections: (source, category, userId, cursor, signal) =>
        client.get(
            `${sourcePath(source)}/collections/${segment(category)}`,
            options(signal, { userId, cursor, limit: 50 })
        ),
    getContainer: (source, type, id, signal) =>
        client.get(`${sourcePath(source)}/containers/${segment(type)}/${segment(id)}`, options(signal)),
    getContainerTracks: (source, type, id, cursor, signal) =>
        client.get(
            `${sourcePath(source)}/containers/${segment(type)}/${segment(id)}/tracks`,
            options(signal, { cursor, limit: 50 })
        ),
    getSongAlbum: (source, id, signal) =>
        client.get(`${sourcePath(source)}/tracks/${segment(id)}/album`, options(signal)),
    getLyric: (source, id, signal) => client.get(`${sourcePath(source)}/tracks/${segment(id)}/lyrics`, options(signal)),
};
