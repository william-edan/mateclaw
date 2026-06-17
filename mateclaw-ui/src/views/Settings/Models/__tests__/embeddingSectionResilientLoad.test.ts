import { describe, expect, it } from 'vitest'
import embeddingSection from '../EmbeddingModelsSection.vue?raw'
import multimodalSection from '../MultimodalSidecarSection.vue?raw'
import modelsIndex from '../index.vue?raw'

/**
 * Regression guard for the "registered user sees no embedding model" bug.
 *
 * The Settings → Models loaders aggregate calls of mixed privilege. A non-global-admin
 * workspace owner gets a 403 on getDefaultEmbedding() (and members on admin-only calls);
 * with Promise.all() that single rejection blanked the whole section. These loaders must
 * use Promise.allSettled() so one failed call never discards the others' results.
 */
describe('Settings → Models loaders survive a 403 on admin-only calls', () => {
  it('EmbeddingModelsSection.loadAll uses allSettled — a 403 on getDefaultEmbedding must not blank the list', () => {
    expect(embeddingSection).toContain('Promise.allSettled')
    expect(embeddingSection).not.toMatch(/Promise\.all\(/)
  })

  it('MultimodalSidecarSection.loadAll uses allSettled', () => {
    expect(multimodalSection).toContain('Promise.allSettled')
    expect(multimodalSection).not.toMatch(/Promise\.all\(/)
  })

  it('Models index onMounted uses allSettled', () => {
    expect(modelsIndex).toContain('Promise.allSettled')
    expect(modelsIndex).not.toMatch(/Promise\.all\(/)
  })
})
