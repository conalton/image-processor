<script setup lang="ts">
import { computed } from 'vue'
import { ContentType } from './generated'
import {
  failedMessage,
  linkLabel,
  pageText,
  rejectedMessage,
  variantLabel,
  waitingMessage,
} from './messages'
import { homeUrl } from './taskUrl'
import { useTaskFlow } from './useTaskFlow'

const ACCEPTED_FILE_TYPES = Object.values(ContentType).join(',')

const { state, inputKey, busy, slowHint, onFileChosen, goHome, checkStatus } = useTaskFlow()

const notice = computed(() => {
  const current = state.value
  switch (current.kind) {
    case 'loadingConfig':
    case 'configFailed':
    case 'idle':
    case 'uploading':
    case 'finished':
      return null
    case 'unconfirmed':
      return pageText.uploadUnconfirmed
    case 'waiting':
    case 'stalled':
      return current.notice
    case 'problem':
      return current.message
    default: {
      const unreachable: never = current
      return unreachable
    }
  }
})
</script>

<template>
  <main>
    <h1>{{ pageText.title }}</h1>
    <p v-if="state.kind === 'configFailed'" role="alert">{{ pageText.configUnavailable }}</p>
    <template v-else>
      <p v-if="state.kind === 'loadingConfig'">{{ pageText.loading }}</p>
      <label v-else>
        {{ pageText.chooseFile }}
        <input :key="inputKey" type="file" :accept="ACCEPTED_FILE_TYPES" :disabled="busy" @change="onFileChosen" />
      </label>
      <!-- In the page from the first render: screen readers announce only text added to an existing live region. -->
      <div role="status" aria-atomic="true">
        <p v-if="notice">{{ notice }}</p>
        <p v-if="state.kind === 'uploading'">{{ pageText.uploading }}</p>
        <p v-else-if="state.kind === 'waiting'">{{ waitingMessage(state.origin, state.stage) }}</p>
        <template v-else-if="state.kind === 'finished'">
          <p v-if="state.task.status === 'DONE'">{{ pageText.done }}</p>
          <section v-else-if="state.task.status === 'INPUT_REJECTED'">
            <p>{{ rejectedMessage(state.task) }}</p>
            <p>{{ pageText.chooseAnother }}</p>
          </section>
          <section v-else-if="state.task.status === 'FAILED'">
            <p>{{ failedMessage(state.task) }}</p>
            <p>{{ pageText.chooseAnother }}</p>
          </section>
        </template>
        <p v-if="slowHint">{{ slowHint }}</p>
      </div>
      <p v-if="state.kind === 'unconfirmed'">
        <button type="button" @click="checkStatus">{{ pageText.checkStatus }}</button>
        <button type="button" @click="goHome">{{ pageText.uploadAgain }}</button>
      </p>
      <ul v-if="state.kind === 'finished' && state.task.status === 'DONE'">
        <li>
          {{ pageText.original }}
          <a
            :href="state.task.original.viewUrl"
            :aria-label="linkLabel(pageText.view, pageText.original)"
            target="_blank"
            rel="noopener"
            >{{ pageText.view }}</a
          >
          <a :href="state.task.original.downloadUrl" :aria-label="linkLabel(pageText.download, pageText.original)">{{
            pageText.download
          }}</a>
        </li>
        <li v-for="variant in state.task.variants" :key="variant.name">
          <img :src="variant.viewUrl" alt="" loading="lazy" decoding="async" />
          {{ variantLabel(variant) }}
          <a
            :href="variant.viewUrl"
            :aria-label="linkLabel(pageText.view, variantLabel(variant))"
            target="_blank"
            rel="noopener"
            >{{ pageText.view }}</a
          >
          <a :href="variant.downloadUrl" :aria-label="linkLabel(pageText.download, variantLabel(variant))">{{
            pageText.download
          }}</a>
        </li>
      </ul>
      <p v-if="state.kind !== 'loadingConfig'">
        <a :href="homeUrl()" @click.prevent="goHome">{{ pageText.newUpload }}</a>
      </p>
    </template>
  </main>
</template>

<style>
body {
  margin: 0;
  font-family: system-ui, sans-serif;
  line-height: 1.5;
  color: #1f2933;
  background: #f5f7fa;
}

main {
  max-width: 40rem;
  margin: 0 auto;
  padding: 2rem 1rem;
}

label {
  display: block;
  margin-bottom: 1rem;
}

ul {
  padding: 0;
  list-style: none;
}

li {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.5rem 1rem;
  padding: 0.5rem 0;
  border-bottom: 1px solid #d9e2ec;
}

li img {
  max-width: 100%;
  max-height: 96px;
  object-fit: contain;
}

li a:first-of-type {
  margin-left: auto;
}

a {
  color: #0b69a3;
}
</style>
