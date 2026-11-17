import { applyCoverArt, applyTags } from 'taglib-wasm/simple';

self.onmessage = async ({ data }) => {
    const { id, audio, metadata, cover, coverType } = data;
    try {
        const tags = {};
        if (metadata.artist) tags.artist = metadata.artist;
        if (metadata.album) tags.album = metadata.album;
        let tagged = await applyTags(new Uint8Array(audio), tags);
        if (cover) tagged = await applyCoverArt(tagged, new Uint8Array(cover), coverType);
        const buffer = tagged.slice().buffer;
        self.postMessage({ id, buffer, coverEmbedded: Boolean(cover) }, [buffer]);
    } catch (error) {
        self.postMessage({ id, error: error instanceof Error ? error.message : 'Tag writing failed' });
    }
};
