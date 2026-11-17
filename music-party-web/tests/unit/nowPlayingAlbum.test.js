import { afterEach, describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { baseParse } from '@vue/compiler-dom';
import { compile, createApp, nextTick, reactive } from 'vue';

const layouts = [
    ['player bar (desktop and mobile)', 'src/components/PlayerControl.vue', 'nowPlaying'],
    ['lite mode (desktop and mobile)', 'src/components/layout/MainLayout.vue', 'playerStore'],
];

function metadataTemplate(path) {
    const component = readFileSync(path, 'utf8');
    const source = component.slice(
        component.indexOf('<template>') + '<template>'.length,
        component.lastIndexOf('</template>')
    );
    const ast = baseParse(source);
    let metadata;
    function visit(node, parent) {
        if (node.tag === 'p' && node.props.some((prop) => prop.exp?.content.includes('albumName'))) {
            metadata = source.slice(parent.loc.start.offset, parent.loc.end.offset);
        }
        node.children?.forEach((child) => visit(child, node));
    }
    visit(ast);
    if (!metadata) throw new Error(`Missing album metadata in ${path}`);
    return metadata;
}

let app;
afterEach(() => {
    app?.unmount();
    app = null;
});

describe.each(layouts)('%s album metadata', (_label, path, binding) => {
    function mount(music) {
        const state = reactive({ connected: true, nowPlaying: music ? { music } : null });
        const context =
            binding === 'nowPlaying'
                ? {
                      player: state,
                      get nowPlaying() {
                          return state.nowPlaying;
                      },
                  }
                : { playerStore: state };
        const host = document.createElement('div');
        app = createApp({ setup: () => context, render: compile(metadataTemplate(path)) });
        app.mount(host);
        return { host, state };
    }

    it.each(['netease', 'qqmusic'])('shows the %s album below the artist', (platform) => {
        const { host } = mount({ name: 'Song', artists: ['Artist'], platform, kind: 'SONG', albumName: 'Album' });
        const album = host.querySelector('p[title="Album"]');
        expect(album?.textContent.trim()).toBe('Album');
        expect(album?.previousElementSibling.textContent.trim()).toBe('Artist');
    });

    it.each([undefined, null, '', '   '])('omits missing or blank album %s without a placeholder', (albumName) => {
        const { host } = mount({ name: 'Song', artists: ['Artist'], kind: 'SONG', albumName });
        expect(host.querySelector('p[title]')).toBeNull();
    });

    it('does not show album metadata for DJ voice segments', () => {
        const { host } = mount({ name: 'DJ', artists: ['DJ'], kind: 'VOICE', albumName: 'Not a song album' });
        expect(host.querySelector('p[title]')).toBeNull();
    });

    it('truncates long names while retaining the full title', () => {
        const albumName = 'An exceptionally long album name '.repeat(10);
        const { host } = mount({ name: 'Song', artists: ['Artist'], albumName });
        const album = host.querySelector('p[title]');
        expect(album?.classList.contains('truncate')).toBe(true);
        expect(album?.getAttribute('title')).toBe(albumName);
        expect(album?.textContent.trim()).toBe(albumName.trim());
    });

    it('removes the previous album when changing tracks or returning to standby', async () => {
        const { host, state } = mount({ name: 'Song', artists: ['Artist'], albumName: 'First album' });
        state.nowPlaying = { music: { name: 'Next', artists: ['Other artist'], albumName: null } };
        await nextTick();
        expect(host.querySelector('p[title]')).toBeNull();
        state.nowPlaying = null;
        await nextTick();
        expect(host.querySelector('p[title]')).toBeNull();
    });
});
