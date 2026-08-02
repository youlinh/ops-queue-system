# Apple Design Full-Site UI Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild the complete Vue frontend around the approved Apple Design prototype while preserving every existing business rule, permission, API contract, and workflow.

**Architecture:** A tokenized CSS foundation and a small set of focused Vue UI primitives replace the monolithic legacy theme. `AppLayout` owns responsive navigation and route-aware chrome; `/tasks/:id` renders the task list as its route page while a global `TaskDetailSheet` supplies the desktop right sheet or mobile bottom sheet. Gesture physics, history-close behavior, and focus containment live in testable TypeScript composables rather than a new UI framework.

**Tech Stack:** Vue 3.5, TypeScript 5.9, Vue Router 4.5, Pinia 3, Axios 1.12, Vite 7, Vitest 3, Vue Test Utils 2.4, Playwright 1.55, CSS custom properties, Pointer Events, `requestAnimationFrame`

## Global Constraints

- The approved design source is `docs/superpowers/specs/2026-08-01-apple-design-full-ui-refactor-design.md` and the local prototype is `.superpowers/brainstorm/423-1785569630/content/apple-design-fluid-v2.html`.
- Backend endpoints, database schema, task allocation rules, role permissions, and audit behavior must not change.
- Page ground is exactly `#f5f5f7`; content surfaces are `#ffffff`; primary text is `#1d1d1f`; body text is `#424245`; secondary text is `#6e6e73`; tertiary text is `#86868b`; hairlines are `rgba(0,0,0,0.07)`.
- Primary action is exactly `#0071e3`; link text is `#0066cc`; live/success is `#30d158`; attention is `#ff6b00`.
- The font stack starts with `-apple-system`, `BlinkMacSystemFont`, `SF Pro Text`, `SF Pro Display`, and `PingFang SC`; enable `font-optical-sizing: auto`.
- Status color stays inside dots, text, or small pills; ordinary content remains grayscale.
- Glass is limited to overlapping structural chrome and overlays; ordinary content panels remain solid white.
- All actionable targets are at least 44px; keyboard focus is visible; numbers use tabular figures.
- Desktop task detail enters/exits on the right; mobile task detail enters/exits at the bottom; enter and exit use the same path.
- Gesture animation starts from the current presentation value, inherits release velocity, and remains interruptible.
- `prefers-reduced-motion`, `prefers-reduced-transparency`, and `prefers-contrast` each receive an explicit fallback.
- Do not add a UI framework or a runtime animation dependency.
- Every behavior change begins with a failing automated test, and each task ends in a focused commit.
- Preserve the user's unrelated changes in `deploy/api.Dockerfile`, `deploy/web.Dockerfile`, `deploy/maven-settings.xml`, `scripts/deploy-local.ps1`, and `start-opsqueue.bat`; never stage them with UI work.

## File and Module Map

### New shared files

- `frontend/src/styles/tokens.css`: visual tokens and motion constants.
- `frontend/src/styles/base.css`: reset, typography, focus, accessibility media preferences.
- `frontend/src/styles/layout.css`: app shell, containers, desktop/tablet/mobile navigation.
- `frontend/src/styles/components.css`: panels, buttons, forms, tables, status, dialogs, sheets, toasts.
- `frontend/src/components/ui/icons.ts`: typed inline-SVG path registry.
- `frontend/src/components/ui/AppIcon.vue`: one accessible line-icon renderer.
- `frontend/src/components/ui/AppDialog.vue`: modal scrim, focus containment, Escape, and return-focus behavior.
- `frontend/src/components/ui/useFocusContainment.ts`: reusable focus entry, loop, and restoration.
- `frontend/src/features/tasks/useSpringSheet.ts`: sheet gesture math and animation controller.
- `frontend/src/features/tasks/useRouteSheet.ts`: close/back/fallback policy for route-backed sheets.
- `frontend/src/features/tasks/TaskDetailContent.vue`: task loading, operator loading, details, timeline, actions, retry.
- `frontend/src/features/tasks/TaskDetailSheet.vue`: route-aware desktop/mobile sheet shell.
- `frontend/src/features/workspace/WorkspacePage.vue`: greeting, daily counts, action hierarchy, and today's queue.

### Existing files with new responsibilities

- `frontend/src/styles.css`: import-only style entry in a deterministic order.
- `frontend/src/app/router.ts`: named task routes and `taskSheet` route metadata.
- `frontend/src/layout/AppLayout.vue`: app chrome, scroll edge, responsive navigation, global task sheet.
- `frontend/src/layout/navigation.ts`: typed icon names and deterministic mobile primary/overflow mapping.
- `frontend/src/layout/RoleNavigation.vue`: desktop sidebar, tablet icon rail, mobile bottom bar and More sheet.
- `frontend/src/features/tasks/TaskQueuePage.vue`: unified task list, list state, route-sheet links.
- `frontend/src/features/tasks/TaskFilters.vue`: segmented common filters and disclosed advanced filters.
- `frontend/src/features/tasks/TaskCreatePage.vue`: grouped form panels and inline validation.
- `frontend/src/features/tasks/TaskActions.vue`: shared dialog surface and stable inline mutation feedback.
- `frontend/src/features/auth/*.vue`, `frontend/src/features/roster/*.vue`, `frontend/src/features/people/*.vue`, `frontend/src/features/reporting/*.vue`, `frontend/src/features/audit/*.vue`, `frontend/src/pages/*.vue`: migrate to the shared visual and interaction language without changing API calls.
- `frontend/src/features/notifications/NotificationToasts.vue`: floating material, immediate close feedback, reduced-motion behavior.
- `frontend/playwright.config.ts`, `frontend/tests/e2e/helpers.ts`, and a new UI E2E spec: desktop/mobile route-sheet and responsive acceptance.

---

### Task 1: Establish the Tokenized Visual Foundation

**Files:**
- Create: `frontend/src/styles/tokens.css`
- Create: `frontend/src/styles/base.css`
- Create: `frontend/src/styles/layout.css`
- Create: `frontend/src/styles/components.css`
- Create: `frontend/src/styles/__tests__/design-tokens.spec.ts`
- Modify: `frontend/src/styles.css`

**Interfaces:**
- Produces: CSS custom properties prefixed `--ui-`, shared semantic classes, and accessibility media-query contracts consumed by every later task.

- [ ] **Step 1: Write a failing token contract test**

```ts
// frontend/src/styles/__tests__/design-tokens.spec.ts
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const tokens = readFileSync(new URL('../tokens.css', import.meta.url), 'utf8')
const base = readFileSync(new URL('../base.css', import.meta.url), 'utf8')

describe('Apple Design token contract', () => {
  it('defines the approved color, type, radius, shadow, and motion values', () => {
    expect(tokens).toContain('--ui-ground: #f5f5f7')
    expect(tokens).toContain('--ui-surface: #ffffff')
    expect(tokens).toContain('--ui-text: #1d1d1f')
    expect(tokens).toContain('--ui-accent: #0071e3')
    expect(tokens).toContain('--ui-radius-panel: 22px')
    expect(tokens).toContain('--ui-radius-sheet: 26px')
    expect(tokens).toContain('--ui-ease-spring: cubic-bezier(.32, .72, 0, 1)')
  })

  it('defines all three accessibility preference fallbacks', () => {
    expect(base).toContain('@media (prefers-reduced-motion: reduce)')
    expect(base).toContain('@media (prefers-reduced-transparency: reduce)')
    expect(base).toContain('@media (prefers-contrast: more)')
  })
})
```

- [ ] **Step 2: Run the contract test and verify it fails because the style modules do not exist**

Run: `corepack pnpm --dir frontend exec vitest run src/styles/__tests__/design-tokens.spec.ts`

Expected: FAIL with a missing `tokens.css` or `base.css` error.

- [ ] **Step 3: Create the four style modules and reduce `styles.css` to ordered imports**

```css
/* frontend/src/styles/tokens.css */
:root {
  --ui-ground: #f5f5f7;
  --ui-surface: #ffffff;
  --ui-hover: #fbfbfd;
  --ui-text: #1d1d1f;
  --ui-text-body: #424245;
  --ui-text-secondary: #6e6e73;
  --ui-text-tertiary: #86868b;
  --ui-hairline: rgba(0, 0, 0, .07);
  --ui-accent: #0071e3;
  --ui-accent-link: #0066cc;
  --ui-live: #30d158;
  --ui-attention: #ff6b00;
  --ui-radius-pill: 999px;
  --ui-radius-small: 12px;
  --ui-radius-card: 18px;
  --ui-radius-panel: 22px;
  --ui-radius-sheet: 26px;
  --ui-shadow-panel: 0 1px 3px rgba(0,0,0,.05), 0 14px 40px rgba(0,0,0,.05);
  --ui-shadow-overlay: 0 2px 8px rgba(0,0,0,.10), 0 30px 80px rgba(0,0,0,.24);
  --ui-ease-spring: cubic-bezier(.32, .72, 0, 1);
  --ui-ease-out: cubic-bezier(.25, 1, .5, 1);
  --ui-sidebar-width: 224px;
  --ui-content-max: 1160px;
}
```

```css
/* frontend/src/styles.css */
@import './styles/tokens.css';
@import './styles/base.css';
@import './styles/layout.css';
@import './styles/components.css';
```

`base.css` must set the approved system font, optical sizing, cool page ground, visible `:focus-visible`, tabular numbers on `.ui-number`, 44px minimum action height, and the three explicit preference media queries. `layout.css` must define `.app-shell`, `.sidebar`, `.topbar`, `.page-content`, `.page-container`, and the 920px/680px responsive states. `components.css` must define solid `.ui-panel`, pill buttons, grouped fields, unified rows, status pills, overlay material, and toast material only through `var(--ui-*)` values.

- [ ] **Step 4: Run the token test, existing unit suite, typecheck, and build**

Run:

```powershell
corepack pnpm --dir frontend exec vitest run src/styles/__tests__/design-tokens.spec.ts
corepack pnpm --dir frontend exec vitest run
corepack pnpm --dir frontend typecheck
corepack pnpm --dir frontend build
```

Expected: all commands PASS; pages may still look partly legacy because later tasks migrate their classes.

- [ ] **Step 5: Commit only the visual foundation**

```powershell
git add frontend/src/styles.css frontend/src/styles frontend/src/styles/__tests__/design-tokens.spec.ts
git commit -m "style: add Apple Design visual foundation"
```

### Task 2: Build Icons and Accessible Modal Primitives

**Files:**
- Create: `frontend/src/components/ui/icons.ts`
- Create: `frontend/src/components/ui/AppIcon.vue`
- Create: `frontend/src/components/ui/useFocusContainment.ts`
- Create: `frontend/src/components/ui/AppDialog.vue`
- Create: `frontend/src/components/ui/__tests__/AppIcon.spec.ts`
- Create: `frontend/src/components/ui/__tests__/AppDialog.spec.ts`

**Interfaces:**
- Produces: `AppIconName`, `<AppIcon name decorative?>`, `<AppDialog :open labelled-by @close>`, and `containFocus(container, returnTarget): () => void`.
- Consumes: Task 1 tokens and overlay classes.

- [ ] **Step 1: Write failing icon and dialog behavior tests**

```ts
// frontend/src/components/ui/__tests__/AppDialog.spec.ts
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import AppDialog from '../AppDialog.vue'

it('closes on Escape and restores focus to the trigger', async () => {
  const trigger = document.createElement('button')
  document.body.append(trigger)
  trigger.focus()
  const wrapper = mount(AppDialog, {
    attachTo: document.body,
    props: { open: true, labelledBy: 'dialog-title' },
    slots: { default: '<h2 id="dialog-title">确认操作</h2><button>确定</button>' },
  })
  await wrapper.get('[role="dialog"]').trigger('keydown', { key: 'Escape' })
  expect(wrapper.emitted('close')).toHaveLength(1)
  await wrapper.setProps({ open: false })
  expect(document.activeElement).toBe(trigger)
  wrapper.unmount()
  trigger.remove()
})
```

```ts
// frontend/src/components/ui/__tests__/AppIcon.spec.ts
import { mount } from '@vue/test-utils'
import { expect, it } from 'vitest'
import AppIcon from '../AppIcon.vue'

it('renders one typed 24-grid line icon without exposing decorative SVG', () => {
  const wrapper = mount(AppIcon, { props: { name: 'workspace', decorative: true } })
  expect(wrapper.get('svg').attributes('viewBox')).toBe('0 0 24 24')
  expect(wrapper.get('svg').attributes('aria-hidden')).toBe('true')
})
```

- [ ] **Step 2: Run the focused tests and verify missing-component failures**

Run: `corepack pnpm --dir frontend exec vitest run src/components/ui/__tests__`

Expected: FAIL because `AppDialog.vue` and `AppIcon.vue` do not exist.

- [ ] **Step 3: Implement typed icons, focus containment, and the dialog contract**

```ts
// frontend/src/components/ui/icons.ts
export type AppIconName =
  | 'workspace' | 'create' | 'tasks' | 'roster'
  | 'people' | 'reports' | 'audit' | 'more' | 'close'

export const iconPaths: Record<AppIconName, readonly string[]> = {
  workspace: ['M4 13h6V4H4z', 'M14 20h6v-9h-6z', 'M4 20h6v-3H4z', 'M14 7h6V4h-6z'],
  create: ['M12 5v14', 'M5 12h14'],
  tasks: ['M7 5h13', 'M7 12h13', 'M7 19h13', 'M3.5 5h.01', 'M3.5 12h.01', 'M3.5 19h.01'],
  roster: ['M5 3v3', 'M19 3v3', 'M4 9h16', 'M5 5h14a1 1 0 0 1 1 1v14H4V6a1 1 0 0 1 1-1z'],
  people: ['M16 20v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2', 'M9 10a4 4 0 1 0 0-8 4 4 0 0 0 0 8z', 'M16 4a4 4 0 0 1 0 8', 'M18 14a4 4 0 0 1 4 4v2'],
  reports: ['M4 20V10', 'M10 20V4', 'M16 20v-7', 'M20 20H2'],
  audit: ['M6 3h12v18H6z', 'M9 8h6', 'M9 12h6', 'M9 16h4'],
  more: ['M5 12h.01', 'M12 12h.01', 'M19 12h.01'],
  close: ['M6 6l12 12', 'M18 6L6 18'],
}
```

`containFocus` must focus the first focusable child, loop Tab/Shift+Tab inside the container, and return a cleanup function that removes listeners and restores the supplied trigger. `AppDialog` must use `Teleport`, `role="dialog"`, `aria-modal="true"`, `aria-labelledby`, Escape handling, scrim close, and a close request rather than mutating caller state.

- [ ] **Step 4: Run component tests and the full unit suite**

Run:

```powershell
corepack pnpm --dir frontend exec vitest run src/components/ui/__tests__
corepack pnpm --dir frontend exec vitest run
```

Expected: PASS.

- [ ] **Step 5: Commit the primitives**

```powershell
git add frontend/src/components/ui
git commit -m "feat: add accessible Apple UI primitives"
```

### Task 3: Rebuild the App Shell and Role Navigation

**Files:**
- Modify: `frontend/src/layout/navigation.ts`
- Modify: `frontend/src/layout/RoleNavigation.vue`
- Modify: `frontend/src/layout/AppLayout.vue`
- Modify: `frontend/src/layout/__tests__/RoleNavigation.spec.ts`
- Create: `frontend/src/layout/__tests__/AppLayout.spec.ts`

**Interfaces:**
- Produces: `mobileNavigationFor(roles): { primary: NavigationItem[]; overflow: NavigationItem[] }` and a responsive navigation that shares the existing `navigationFor` permission filter.
- Consumes: `AppIconName`, `AppIcon`, `AppDialog`, and Task 1 shell classes.

- [ ] **Step 1: Extend navigation tests with the exact mobile mapping and shell scroll-edge behavior**

```ts
import { mobileNavigationFor } from '../navigation'

it('maps leader navigation to three direct items and More overflow', () => {
  const result = mobileNavigationFor(['LEADER'])
  expect(result.primary.map(item => item.to)).toEqual(['/workspace', '/tasks', '/people'])
  expect(result.overflow.map(item => item.to)).toEqual(['/rosters', '/reports', '/audit'])
})

it('gives combined roles the developer create action before leader actions', () => {
  const result = mobileNavigationFor(['DEVELOPER', 'OPERATOR', 'LEADER'])
  expect(result.primary.map(item => item.to)).toEqual(['/workspace', '/tasks', '/tasks/new'])
  expect(result.overflow.map(item => item.to)).toEqual(['/rosters', '/people', '/reports', '/audit'])
})
```

`AppLayout.spec.ts` must mount the layout with a mocked authenticated store, dispatch `scroll`, and assert `.topbar--scrolled` appears only when `window.scrollY > 8`.

- [ ] **Step 2: Run layout tests and verify the new exports/classes are missing**

Run: `corepack pnpm --dir frontend exec vitest run src/layout/__tests__`

Expected: FAIL for missing `mobileNavigationFor` and `.topbar--scrolled`.

- [ ] **Step 3: Implement the approved desktop/tablet/mobile shell**

```ts
// frontend/src/layout/navigation.ts
export function mobileNavigationFor(roles: readonly Role[]) {
  const granted = navigationFor(roles)
  const directTarget = roles.includes('DEVELOPER')
    ? '/tasks/new'
    : roles.includes('LEADER') ? '/people' : '/reports'
  const directPaths = ['/workspace', '/tasks', directTarget]
  return {
    primary: directPaths.map(path => granted.find(item => item.to === path)).filter(Boolean) as NavigationItem[],
    overflow: granted.filter(item => !directPaths.includes(item.to)),
  }
}
```

Replace character icons with typed `AppIconName` values. `RoleNavigation` must render desktop labels, the tablet icon rail, a maximum-four-item mobile bar, and a More button only when `overflow.length > 0`. `AppLayout` must remove the permanent legacy status strip, keep duty/user/sign-out behavior, add the translucent scroll edge, and preserve `NotificationToasts`.

- [ ] **Step 4: Run layout tests, full unit tests, typecheck, and build**

Run:

```powershell
corepack pnpm --dir frontend exec vitest run src/layout/__tests__
corepack pnpm --dir frontend exec vitest run
corepack pnpm --dir frontend typecheck
corepack pnpm --dir frontend build
```

Expected: PASS; role-visible labels remain identical to existing tests.

- [ ] **Step 5: Commit the shell**

```powershell
git add frontend/src/layout
git commit -m "feat: rebuild responsive application shell"
```

### Task 4: Implement Interruptible Sheet Physics

**Files:**
- Create: `frontend/src/features/tasks/useSpringSheet.ts`
- Create: `frontend/src/features/tasks/__tests__/useSpringSheet.spec.ts`

**Interfaces:**
- Produces: `PointerSample`, `projectEndpoint`, `rubberbandDistance`, `velocityFromSamples`, `shouldDismissSheet`, and `useSpringSheet(options)`.
- `useSpringSheet` returns `{ position, progress, dragging, onPointerDown, onPointerMove, onPointerUp, open, close, stop }` and updates only transform/opacity-facing refs.

- [ ] **Step 1: Write failing deterministic physics tests**

```ts
import { describe, expect, it } from 'vitest'
import {
  projectEndpoint,
  rubberbandDistance,
  shouldDismissSheet,
  velocityFromSamples,
} from '../useSpringSheet'

describe('sheet physics', () => {
  it('derives release velocity from only the recent sample window', () => {
    expect(velocityFromSamples([
      { position: 0, time: 0 },
      { position: 20, time: 100 },
      { position: 60, time: 150 },
    ], 150, 90)).toBeCloseTo(800)
  })

  it('projects a fast outward gesture past the dismiss threshold', () => {
    const projected = projectEndpoint(120, 900, .99)
    expect(projected).toBeGreaterThan(200)
    expect(shouldDismissSheet(projected, 460, 900)).toBe(true)
  })

  it('resists inward overshoot without a hard stop', () => {
    expect(rubberbandDistance(-100, 460)).toBeLessThan(0)
    expect(Math.abs(rubberbandDistance(-100, 460))).toBeLessThan(100)
  })
})
```

- [ ] **Step 2: Run the physics test and verify missing exports**

Run: `corepack pnpm --dir frontend exec vitest run src/features/tasks/__tests__/useSpringSheet.spec.ts`

Expected: FAIL because `useSpringSheet.ts` is missing.

- [ ] **Step 3: Implement exact math and a requestAnimationFrame spring controller**

```ts
export interface PointerSample { position: number; time: number }

export function projectEndpoint(position: number, velocity: number, rate = .99) {
  return position + (velocity / 1000) * rate / (1 - rate)
}

export function rubberbandDistance(overshoot: number, dimension: number, constant = .55) {
  return (overshoot * dimension * constant)
    / (dimension + constant * Math.abs(overshoot))
}

export function shouldDismissSheet(projected: number, extent: number, velocity: number) {
  return projected > extent * .38 || velocity > 620
}
```

`velocityFromSamples` must retain samples newer than `now - windowMs` and return pixels per second. `useSpringSheet` must call `setPointerCapture`, keep the grab offset, cancel the active frame on a new pointer-down, continue from the current `position`, and use stiffness 420/damping 42 for non-bouncy critically damped settling. When reduced motion matches, it must set the target immediately without running a frame loop.

- [ ] **Step 4: Run physics tests, typecheck, and the full unit suite**

Run:

```powershell
corepack pnpm --dir frontend exec vitest run src/features/tasks/__tests__/useSpringSheet.spec.ts
corepack pnpm --dir frontend exec vitest run
corepack pnpm --dir frontend typecheck
```

Expected: PASS.

- [ ] **Step 5: Commit the physics module**

```powershell
git add frontend/src/features/tasks/useSpringSheet.ts frontend/src/features/tasks/__tests__/useSpringSheet.spec.ts
git commit -m "feat: add interruptible task sheet physics"
```

### Task 5: Convert Task Detail to a Route-Backed Sheet

**Files:**
- Create: `frontend/src/features/tasks/useRouteSheet.ts`
- Create: `frontend/src/features/tasks/TaskDetailContent.vue`
- Create: `frontend/src/features/tasks/TaskDetailSheet.vue`
- Create: `frontend/src/features/tasks/__tests__/TaskDetailSheet.spec.ts`
- Modify: `frontend/src/app/router.ts`
- Modify: `frontend/src/app/__tests__/router.spec.ts`
- Modify: `frontend/src/layout/AppLayout.vue`
- Modify: `frontend/src/features/tasks/TaskQueuePage.vue`
- Delete after migration: `frontend/src/features/tasks/TaskDetailPage.vue`

**Interfaces:**
- Produces: named routes `tasks` and `task-detail`, `RouteMeta.taskSheet?: boolean`, and `<TaskDetailSheet :task-id @close>` mounted by `AppLayout`.
- Consumes: Task 2 focus/dialog behavior, Task 4 sheet physics, existing `taskDetail`, `listOperators`, and `TaskActions`.

- [ ] **Step 1: Write failing route and sheet behavior tests**

```ts
// router.spec.ts additions
import { routes } from '../router'

it('renders the task list beneath the named route-backed detail sheet', () => {
  const shell = routes.find(route => route.path === '/')!
  const detail = shell.children!.find(route => route.path === 'tasks/:id')!
  expect(detail.name).toBe('task-detail')
  expect(detail.meta?.taskSheet).toBe(true)
})
```

```ts
// TaskDetailSheet.spec.ts
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { expect, it, vi } from 'vitest'
import TaskDetailSheet from '../TaskDetailSheet.vue'
import * as taskApi from '../task.api'

vi.mock('../task.api')

it('keeps a failed detail request open and exposes retry', async () => {
  vi.mocked(taskApi.taskDetail).mockRejectedValue(new Error('offline'))
  vi.mocked(taskApi.listOperators).mockResolvedValue([])
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/tasks', name: 'tasks', component: { template: '<div />' } },
      { path: '/tasks/:id', name: 'task-detail', component: { template: '<div />' } },
    ],
  })
  await router.push('/tasks/task-1')
  await router.isReady()
  const wrapper = mount(TaskDetailSheet, {
    props: { taskId: 'task-1' },
    global: { plugins: [createPinia(), router] },
  })
  await flushPromises()
  expect(wrapper.get('[role="dialog"]').attributes('aria-modal')).toBe('true')
  expect(wrapper.get('[role="alert"]').text()).toContain('任务详情加载失败')
  expect(wrapper.get('[data-testid="retry-task-detail"]').exists()).toBe(true)
})
```

Add tests that Escape emits close, direct-entry close resolves to `/tasks`, in-app entry chooses `router.back()`, focus returns to the triggering row, and changing `taskId` updates content without remounting the sheet shell.

- [ ] **Step 2: Run focused tests and verify route metadata/components are missing**

Run:

```powershell
corepack pnpm --dir frontend exec vitest run src/app/__tests__/router.spec.ts
corepack pnpm --dir frontend exec vitest run src/features/tasks/__tests__/TaskDetailSheet.spec.ts
```

Expected: FAIL.

- [ ] **Step 3: Implement named routes and the content/sheet split**

```ts
// useRouteSheet.ts
import type { Router } from 'vue-router'

export function canGoBackToApp(back: unknown, currentPath: string): back is string {
  return typeof back === 'string'
    && back.startsWith('/')
    && !back.startsWith('//')
    && back !== currentPath
}

export async function closeTaskSheet(router: Router, currentPath: string) {
  const back = window.history.state?.back
  if (canGoBackToApp(back, currentPath)) router.back()
  else await router.replace({ name: 'tasks' })
}
```

Define `/tasks` as `{ name: 'tasks', component: TaskQueuePage }` and `/tasks/:id` as `{ name: 'task-detail', component: TaskQueuePage, props: { dashboard: false }, meta: { taskSheet: true, ... } }`. `AppLayout` must mount one `TaskDetailSheet` when `route.meta.taskSheet` is true and pass `String(route.params.id)`. `TaskDetailContent` moves all existing load/retry/timeline/action behavior. `TaskDetailSheet` owns material, direction, scrim, focus, Escape, and drag; it must remain closable while detail or mutation requests are pending.

- [ ] **Step 4: Run focused tests, all task tests, router tests, typecheck, and build**

Run:

```powershell
corepack pnpm --dir frontend exec vitest run src/app/__tests__/router.spec.ts src/features/tasks/__tests__
corepack pnpm --dir frontend typecheck
corepack pnpm --dir frontend build
```

Expected: PASS; `rg "TaskDetailPage" frontend/src` returns no imports before deleting the old file.

- [ ] **Step 5: Commit the route-backed detail sheet**

```powershell
git add frontend/src/app frontend/src/layout/AppLayout.vue frontend/src/features/tasks
git commit -m "feat: open task details in a route-backed sheet"
```

### Task 6: Rebuild the Workspace, Task Queue, and Filters

**Files:**
- Create: `frontend/src/features/workspace/WorkspacePage.vue`
- Create: `frontend/src/features/workspace/__tests__/WorkspacePage.spec.ts`
- Create: `frontend/src/features/tasks/__tests__/TaskQueuePage.spec.ts`
- Modify: `frontend/src/app/router.ts`
- Modify: `frontend/src/layout/AppLayout.vue`
- Modify: `frontend/src/features/tasks/TaskQueuePage.vue`
- Modify: `frontend/src/features/tasks/TaskFilters.vue`

**Interfaces:**
- Produces: `WorkspacePage` as the `/workspace` component and task rows with `data-testid="task-row"` plus named-route navigation.
- Consumes: existing `taskCounts`, `searchTasks`, duty summary, Task 1 unified panel styles, and Task 5 `task-detail` route.

- [ ] **Step 1: Write failing workspace and task-list tests**

```ts
// TaskQueuePage.spec.ts shared setup
import { mount, flushPromises } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { vi } from 'vitest'
import TaskQueuePage from '../TaskQueuePage.vue'
import * as taskApi from '../task.api'
import type { TaskPage, TaskRow } from '../task.types'

vi.mock('../task.api')

const router = createRouter({
  history: createMemoryHistory(),
  routes: [
    { path: '/tasks', name: 'tasks', component: TaskQueuePage },
    { path: '/tasks/:id', name: 'task-detail', component: TaskQueuePage },
  ],
})

function taskPage(content: TaskRow[] = []): TaskPage {
  return { content, page: 0, size: 20, totalElements: content.length, totalPages: content.length ? 1 : 0 }
}

function taskRow(): TaskRow {
  return {
    id: 'task-1', ticketNumber: 'OPS-20260801-0001', category: 'VERSION_RELEASE',
    systemName: '支付系统', processNumber: 'REL-1', operationStart: '2026-08-01T14:00:00+08:00',
    operationEnd: '2026-08-01T15:00:00+08:00', creatorId: 'dev-1', creatorName: '开发甲',
    currentAssigneeId: 'operator-1', currentAssigneeName: '运维甲', status: 'PENDING',
    estimatedMinutes: 60, actualMinutes: null, assignmentRule: 'DAY_SECOND', canCall: false,
    canComplete: false, canTransfer: false, needsManualAttention: false, createdAt: '2026-08-01T09:00:00+08:00',
  }
}

async function mountQueueWithOneTask() {
  vi.mocked(taskApi.searchTasks).mockResolvedValue(taskPage([taskRow()]))
  await router.push('/tasks')
  await router.isReady()
  const wrapper = mount(TaskQueuePage, { global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

it('shows daily counts in one overview surface and today's queue below it', async () => {
  vi.mocked(taskApi.taskCounts).mockResolvedValue({ pending: 12, inProgress: 4, manualAttention: 2 })
  vi.mocked(taskApi.searchTasks).mockResolvedValue(taskPage())
  const wrapper = mount(WorkspacePage, { global: { plugins: [router] } })
  await flushPromises()
  expect(wrapper.get('[data-testid="workspace-overview"]').text()).toContain('12')
  expect(wrapper.get('[data-testid="workspace-overview"]').text()).toContain('4')
  expect(wrapper.get('[data-testid="workspace-overview"]').text()).toContain('2')
  expect(wrapper.findAll('.metric-card')).toHaveLength(0)
})

it('opens a task through the named route and preserves filter state', async () => {
  const wrapper = await mountQueueWithOneTask()
  await wrapper.get('[data-testid="task-row"]').trigger('click')
  expect(router.currentRoute.value.name).toBe('task-detail')
  expect(router.currentRoute.value.params.id).toBe('task-1')
})
```

- [ ] **Step 2: Run focused tests and verify missing workspace/test hooks**

Run: `corepack pnpm --dir frontend exec vitest run src/features/workspace src/features/tasks/__tests__/TaskQueuePage.spec.ts`

Expected: FAIL.

- [ ] **Step 3: Implement the approved hierarchy without changing API semantics**

`WorkspacePage` must render the greeting, Shanghai date, one `data-testid="workspace-overview"` panel with three hairline-separated metrics, and `<TaskQueuePage dashboard />`. Move count fetching and the `ops-task-changed` refresh listener from `AppLayout` into `WorkspacePage`. `TaskQueuePage` must keep its request-sequence protection, pagination, error state, and dashboard date reset; replace the legacy table wrapper with a unified responsive row layout. `TaskFilters` must expose common status/category filters in the first row and keep date/system/creator/assignee controls in a disclosed advanced region.

```ts
function openTask(id: string): void {
  router.push({ name: 'task-detail', params: { id } })
}
```

- [ ] **Step 4: Run workspace/task tests and regression commands**

Run:

```powershell
corepack pnpm --dir frontend exec vitest run src/features/workspace src/features/tasks/__tests__
corepack pnpm --dir frontend exec vitest run
corepack pnpm --dir frontend typecheck
corepack pnpm --dir frontend build
```

Expected: PASS.

- [ ] **Step 5: Commit the workspace and queue**

```powershell
git add frontend/src/features/workspace frontend/src/features/tasks/TaskQueuePage.vue frontend/src/features/tasks/TaskFilters.vue frontend/src/app/router.ts frontend/src/layout/AppLayout.vue
git commit -m "feat: rebuild workspace and task queue"
```

### Task 7: Migrate Task Creation and Task Action Dialogs

**Files:**
- Modify: `frontend/src/features/tasks/TaskCreatePage.vue`
- Modify: `frontend/src/features/tasks/TaskActions.vue`
- Modify: `frontend/src/features/tasks/__tests__/TaskCreatePage.spec.ts`
- Modify: `frontend/src/features/tasks/__tests__/TaskActions.spec.ts`

**Interfaces:**
- Consumes: `AppDialog`, shared field/panel/button/alert classes, existing task APIs and validation limits.
- Produces: grouped task form panels and task mutation dialogs with focus restoration and stable inline errors.

- [ ] **Step 1: Add failing interaction and accessibility assertions**

In `TaskCreatePage.spec.ts`, reuse its existing `mountPage()` helper. In `TaskActions.spec.ts`, replace the existing `mountActions` helper with the four-argument version below; retain the file's existing `task()` fixture and `operatorId`.

```ts
// TaskCreatePage.spec.ts
it('focuses the first invalid create field and keeps the error beside the form', async () => {
  const wrapper = mountPage()
  await wrapper.get('[data-testid="submit-task"]').trigger('click')
  expect(wrapper.get('[role="alert"]').text()).toContain('请选择任务类别')
  expect(document.activeElement).toBe(wrapper.get('[data-testid="category"]').element)
})

// TaskActions.spec.ts helper replacement
function mountActions(
  currentTask: TaskDetail,
  userId = operatorId,
  roles: Array<'DEVELOPER' | 'OPERATOR' | 'LEADER'> = ['OPERATOR'],
  options: { attachTo?: HTMLElement | string } = {},
) {
  return mount(TaskActions, {
    ...options,
    props: {
      task: currentTask,
      currentUserId: userId,
      roles,
      operators: [
        { id: operatorId, displayName: '运维甲', available: true },
        { id: 'operator-2', displayName: '运维乙', available: true },
      ],
    },
  })
}

it('returns focus to the complete button when its dialog closes', async () => {
  const wrapper = mountActions(task({
    status: 'IN_PROGRESS',
    canCall: false,
    canComplete: true,
  }), operatorId, ['OPERATOR'], { attachTo: document.body })
  const trigger = wrapper.get('[data-testid="complete-task"]')
  await trigger.trigger('click')
  await wrapper.get('[data-testid="cancel-dialog"]').trigger('click')
  expect(document.activeElement).toBe(trigger.element)
  wrapper.unmount()
})
```

- [ ] **Step 2: Run task create/action tests and verify the new behavior fails**

Run: `corepack pnpm --dir frontend exec vitest run src/features/tasks/__tests__/TaskCreatePage.spec.ts src/features/tasks/__tests__/TaskActions.spec.ts`

Expected: FAIL on focus and dialog assertions.

- [ ] **Step 3: Migrate templates while preserving all task rules**

`TaskCreatePage` must group category/system/process, operation window, and supporting information into solid panels; preserve system-name suggestions, 1–1440 minute validation, operation start/end validation, and result contents. Replace dark allocation guidance and colored result borders with a secondary panel and status row. `TaskActions` must use `AppDialog` for complete/transfer, add `data-testid="cancel-dialog"`, keep the exact permission computations, keep the operator-directory retry outside the dialog, and leave the sheet closable during requests.

```ts
function focusFirstInvalid(testId: string): string {
  requestAnimationFrame(() => {
    document.querySelector<HTMLElement>(`[data-testid="${testId}"]`)?.focus()
  })
  return testId
}
```

- [ ] **Step 4: Run task tests, typecheck, and build**

Run:

```powershell
corepack pnpm --dir frontend exec vitest run src/features/tasks/__tests__
corepack pnpm --dir frontend typecheck
corepack pnpm --dir frontend build
```

Expected: PASS; existing call/complete/transfer behavior assertions remain unchanged.

- [ ] **Step 5: Commit task forms and actions**

```powershell
git add frontend/src/features/tasks/TaskCreatePage.vue frontend/src/features/tasks/TaskActions.vue frontend/src/features/tasks/__tests__
git commit -m "feat: migrate task forms and actions to Apple UI"
```

### Task 8: Migrate Authentication and Utility Pages

**Files:**
- Modify: `frontend/src/features/auth/LoginPage.vue`
- Modify: `frontend/src/features/auth/ChangePasswordPage.vue`
- Create: `frontend/src/features/auth/__tests__/AuthPages.spec.ts`
- Modify: `frontend/src/pages/ForbiddenPage.vue`
- Modify: `frontend/src/pages/PlaceholderPage.vue`

**Interfaces:**
- Consumes: existing auth store methods, safe redirect logic, shared form/panel/button classes.
- Produces: the approved single-surface login, grouped password form, and restrained recovery pages.

- [ ] **Step 1: Write failing semantic and behavior tests**

```ts
// AuthPages.spec.ts
import { mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, type Pinia } from 'pinia'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { beforeEach, expect, it, vi } from 'vitest'
import LoginPage from '../LoginPage.vue'
import { useAuthStore } from '../auth.store'

let pinia: Pinia
let router: Router

beforeEach(async () => {
  vi.restoreAllMocks()
  pinia = createPinia()
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/login', component: LoginPage },
      { path: '/workspace', component: { template: '<div />' } },
      { path: '/change-password', component: { template: '<div />' } },
    ],
  })
  await router.push('/login')
  await router.isReady()
})

function mountLoginPage() {
  return mount(LoginPage, { global: { plugins: [pinia, router] } })
}

async function fillAndSubmit(wrapper: VueWrapper) {
  await wrapper.get('[autocomplete="username"]').setValue('dev1')
  await wrapper.get('[autocomplete="current-password"]').setValue('wrong-password')
  await wrapper.get('form').trigger('submit')
}

it('renders login as one form surface without the legacy dark brand split', () => {
  const wrapper = mountLoginPage()
  expect(wrapper.get('form').classes()).toContain('auth-surface')
  expect(wrapper.find('.auth-brand').exists()).toBe(false)
  expect(wrapper.get('[autocomplete="username"]').exists()).toBe(true)
  expect(wrapper.get('[autocomplete="current-password"]').exists()).toBe(true)
})

it('keeps the 401 message inline and does not navigate', async () => {
  vi.spyOn(useAuthStore(), 'signIn').mockRejectedValue({ response: { status: 401 } })
  const wrapper = mountLoginPage()
  await fillAndSubmit(wrapper)
  await vi.waitFor(() => expect(wrapper.get('[role="alert"]').text()).toContain('账号或密码错误'))
  expect(router.currentRoute.value.path).toBe('/login')
})
```

- [ ] **Step 2: Run auth page tests and verify the new surface assertion fails**

Run: `corepack pnpm --dir frontend exec vitest run src/features/auth/__tests__`

Expected: FAIL for missing `.auth-surface` and the legacy split still present.

- [ ] **Step 3: Implement the approved auth and recovery surfaces**

Login must use cool ground plus one centered solid form surface, a compact OPS mark, concise product sentence, labeled fields, nearby error text, and the existing submit/redirect logic. Change Password must use a max-720px grouped form with rules beside the fields and retain the store refresh/redirect behavior. Forbidden and Placeholder pages must show one direct recovery link and no decorative metrics or emoji.

```vue
<main class="auth-layout">
  <form class="auth-surface ui-panel" @submit.prevent="submit">
    <div class="brand-mark" aria-hidden="true">OPS</div>
    <h1>登录运维叫号台</h1>
    <p class="page-summary">进入任务队列，查看分派并完成操作留痕。</p>
    <!-- existing labeled inputs and submit state -->
  </form>
</main>
```

- [ ] **Step 4: Run auth tests, full unit tests, typecheck, and build**

Run:

```powershell
corepack pnpm --dir frontend exec vitest run src/features/auth/__tests__
corepack pnpm --dir frontend exec vitest run
corepack pnpm --dir frontend typecheck
corepack pnpm --dir frontend build
```

Expected: PASS.

- [ ] **Step 5: Commit auth and utility pages**

```powershell
git add frontend/src/features/auth frontend/src/pages
git commit -m "feat: migrate authentication and recovery pages"
```

### Task 9: Migrate Roster and People Management

**Files:**
- Modify: `frontend/src/features/roster/RosterImportPage.vue`
- Modify: `frontend/src/features/roster/RosterPreviewTable.vue`
- Modify: `frontend/src/features/roster/__tests__/RosterImportPage.spec.ts`
- Modify: `frontend/src/features/people/PeoplePage.vue`
- Modify: `frontend/src/features/people/UnavailabilityDialog.vue`
- Modify: `frontend/src/features/people/RedistributionDialog.vue`
- Modify: `frontend/src/features/people/__tests__/RedistributionDialog.spec.ts`

**Interfaces:**
- Consumes: `AppDialog`, route-backed task links, shared panel/table/form styles, existing roster/people APIs.
- Produces: staged roster import UI and shared-material account/unavailability/redistribution workflows.

- [ ] **Step 1: Add failing tests for staged import and close-safe management dialogs**

In `RosterImportPage.spec.ts`, reuse the existing `selectFile` helper. In `RedistributionDialog.spec.ts`, reuse the existing `operator` fixture.

```ts
// RosterImportPage.spec.ts
it('labels preview as the active stage and keeps row errors adjacent to the preview', async () => {
  vi.mocked(rosterApi.previewRoster).mockResolvedValue({
    batchId: 'batch-1',
    valid: false,
    rows: [],
    errors: [{ rowNumber: 7, message: '账号不存在' }],
  })
  const invalidWorkbook = new File(['xlsx'], 'invalid-duty.xlsx')
  const wrapper = mount(RosterImportPage)
  await selectFile(wrapper, invalidWorkbook)
  await flushPromises()
  expect(wrapper.get('[aria-current="step"]').text()).toContain('预览校验')
  expect(wrapper.get('[data-testid="roster-preview"]').text()).toContain('Excel 第 7 行')
})

// RedistributionDialog.spec.ts
it('keeps redistribution failures visible inside the dialog until the user closes it', async () => {
  vi.mocked(peopleApi.previewRedistribution).mockResolvedValue([{
    taskId: 'failed-1',
    ticketNumber: 'OPS-FAILED',
    category: 'DATA_MAINTENANCE',
    systemName: '数据平台',
    operationStart: '2026-08-01T14:00:00+08:00',
    currentAssigneeId: operator.id,
  }])
  vi.mocked(taskApi.searchTasks).mockResolvedValue({
    content: [], page: 0, size: 100, totalElements: 0, totalPages: 0,
  })
  vi.mocked(peopleApi.redistribute).mockResolvedValue({
    sourceOperatorId: operator.id,
    date: '2026-08-01',
    items: [{
      taskId: 'failed-1', ticketNumber: 'OPS-FAILED', success: false,
      previousAssigneeId: operator.id, assigneeId: operator.id,
      needsManualAttention: true, error: '没有可用候选人',
    }],
  })
  const wrapper = mount(RedistributionDialog, {
    props: { operator, date: '2026-08-01', reason: '临时请假' },
    global: {
      stubs: {
        RouterLink: { props: ['to'], template: '<a :href="to"><slot /></a>' },
      },
    },
  })
  await vi.waitFor(() => expect(wrapper.text()).toContain('OPS-FAILED'))
  await wrapper.get('[data-testid="execute-redistribution"]').trigger('click')
  await flushPromises()
  expect(wrapper.get('[role="dialog"]').text()).toContain('没有可用候选人')
  expect(wrapper.get('a[href="/tasks/failed-1"]').exists()).toBe(true)
})
```

- [ ] **Step 2: Run roster/people tests and verify new semantic hooks fail**

Run: `corepack pnpm --dir frontend exec vitest run src/features/roster src/features/people`

Expected: FAIL on the stage and shared-dialog hooks.

- [ ] **Step 3: Migrate management pages without altering mutation order**

Roster must show Upload → Preview → Confirm as a textual stage indicator, preserve the real preview-before-import gate, place validation errors above `data-testid="roster-preview"`, and keep history in one hairline-separated panel. People must place account creation and account list in separate solid panels; role/password/unavailability/redistribution actions use `AppDialog`. Disable remains the only action that requests irreversible confirmation. Redistribution must preserve separate pending/executing sections, per-task results, and `/tasks/:id` links.

```ts
const importStage = computed<'upload' | 'preview' | 'confirmed'>(() => {
  if (successMessage.value) return 'confirmed'
  if (preview.value) return 'preview'
  return 'upload'
})
```

- [ ] **Step 4: Run management tests, typecheck, and build**

Run:

```powershell
corepack pnpm --dir frontend exec vitest run src/features/roster src/features/people
corepack pnpm --dir frontend typecheck
corepack pnpm --dir frontend build
```

Expected: PASS; existing conflict refresh and per-task failure tests still pass.

- [ ] **Step 5: Commit roster and people management**

```powershell
git add frontend/src/features/roster frontend/src/features/people
git commit -m "feat: migrate roster and people management UI"
```

### Task 10: Migrate Reporting and Audit

**Files:**
- Modify: `frontend/src/features/reporting/ReportingPage.vue`
- Create: `frontend/src/features/reporting/__tests__/ReportingPage.spec.ts`
- Modify: `frontend/src/features/audit/AuditLogPage.vue`
- Create: `frontend/src/features/audit/__tests__/AuditLogPage.spec.ts`

**Interfaces:**
- Consumes: existing report/audit APIs and shared panel/filter/status styles.
- Produces: unified metric sections, retained report results during refresh, and route-sheet-style audit detail disclosure.

- [ ] **Step 1: Write failing report refresh and audit disclosure tests**

```ts
// ReportingPage.spec.ts
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { expect, it, vi } from 'vitest'
import { useAuthStore } from '@/features/auth/auth.store'
import * as peopleApi from '@/features/people/people.api'
import ReportingPage from '../ReportingPage.vue'
import * as reportingApi from '../reporting.api'
import type { OperatorMetrics } from '../reporting.api'

vi.mock('@/features/people/people.api')
vi.mock('../reporting.api')

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(done => { resolve = done })
  return { promise, resolve }
}

function metrics(totalTaskCount: number): OperatorMetrics {
  return {
    operatorId: 'operator-1', date: '2026-08-01', month: '2026-08',
    totalTaskCount, pendingCount: 1, inProgressCount: 1, completedCount: totalTaskCount - 2,
    estimatedMinutes: 120, completedActualMinutes: 90,
  }
}

function mountReportingPage() {
  const pinia = createPinia()
  useAuthStore(pinia).user = {
    id: 'operator-1', username: 'ops1', displayName: '运维甲',
    roles: ['OPERATOR'], mustChangePassword: false,
  }
  vi.mocked(peopleApi.listAccounts).mockResolvedValue([])
  return mount(ReportingPage, { global: { plugins: [pinia] } })
}

it('keeps the current metrics visible while a refreshed request is pending', async () => {
  const next = deferred<OperatorMetrics>()
  vi.mocked(reportingApi.dailyReport)
    .mockResolvedValueOnce(metrics(5))
    .mockReturnValueOnce(next.promise)
  vi.mocked(reportingApi.monthlyReport).mockResolvedValue(metrics(8))
  const wrapper = mountReportingPage()
  await vi.waitFor(() => expect(wrapper.text()).toContain('5'))
  await wrapper.get('[data-testid="refresh-report"]').trigger('click')
  expect(wrapper.text()).toContain('5')
  expect(wrapper.get('[role="status"]').text()).toContain('正在更新')
  next.resolve(metrics(6))
  await flushPromises()
  expect(wrapper.text()).toContain('6')
})
```

```ts
// AuditLogPage.spec.ts
import { mount, flushPromises } from '@vue/test-utils'
import { expect, it, vi } from 'vitest'
import * as peopleApi from '@/features/people/people.api'
import AuditLogPage from '../AuditLogPage.vue'
import * as auditApi from '../audit.api'

vi.mock('@/features/people/people.api')
vi.mock('../audit.api')

function mountAuditWithOneEntry() {
  vi.mocked(peopleApi.listAccounts).mockResolvedValue([])
  vi.mocked(auditApi.searchAuditLogs).mockResolvedValue({
    content: [{
      id: 'audit-1', actorId: 'operator-1', action: 'TASK_CALLED',
      objectType: 'TASK', objectId: 'task-1', before: { status: 'PENDING' },
      after: { status: 'IN_PROGRESS' }, sourceIp: '127.0.0.1',
      occurredAt: '2026-08-01T14:00:00+08:00',
    }],
    page: 0, size: 20, totalElements: 1, totalPages: 1,
  })
  return mount(AuditLogPage)
}

it('opens long audit payload in an accessible detail surface', async () => {
  const wrapper = mountAuditWithOneEntry()
  await flushPromises()
  await wrapper.get('[data-testid="open-audit-detail"]').trigger('click')
  expect(wrapper.get('[role="dialog"]').text()).toContain('变更详情')
  expect(wrapper.get('[role="dialog"]').text()).toContain('TASK_CALLED')
})
```

- [ ] **Step 2: Run report/audit tests and verify the new behaviors fail**

Run: `corepack pnpm --dir frontend exec vitest run src/features/reporting src/features/audit`

Expected: FAIL.

- [ ] **Step 3: Implement unified metrics and disclosed audit detail**

Reporting must group related daily/monthly values within one or two white panels, use `.ui-number`, place units/definitions beside values, and retain previous results while setting an inline “正在更新” status. Audit must preserve filtering, pagination, actor resolution, and error behavior; render logs as one scannable list and move JSON/long fields into `AppDialog` instead of a permanent dark code block.

```ts
async function loadReports(): Promise<void> {
  updating.value = Boolean(daily.value || monthly.value)
  errorMessage.value = ''
  try {
    const [nextDaily, nextMonthly] = await Promise.all([
      dailyReport(form.date, form.operatorId),
      monthlyReport(form.month, form.operatorId),
    ])
    daily.value = nextDaily
    monthly.value = nextMonthly
  } catch (error) {
    errorMessage.value = apiErrorMessage(error, '统计加载失败')
  } finally {
    updating.value = false
  }
}
```

- [ ] **Step 4: Run report/audit tests, typecheck, and build**

Run:

```powershell
corepack pnpm --dir frontend exec vitest run src/features/reporting src/features/audit
corepack pnpm --dir frontend typecheck
corepack pnpm --dir frontend build
```

Expected: PASS.

- [ ] **Step 5: Commit reporting and audit**

```powershell
git add frontend/src/features/reporting frontend/src/features/audit
git commit -m "feat: migrate reporting and audit UI"
```

### Task 11: Finish Notifications and Remove Legacy Theme Drift

**Files:**
- Modify: `frontend/src/features/notifications/NotificationToasts.vue`
- Modify: `frontend/src/features/notifications/__tests__/NotificationToasts.spec.ts`
- Create: `frontend/src/styles/__tests__/legacy-theme.spec.ts`
- Modify: `frontend/src/styles/components.css`
- Verify only: `frontend/src/styles.css` and all `frontend/src/**/*.vue` files through the exhaustive Step 2 guard

**Interfaces:**
- Consumes: Task 1 tokens and approved overlay material.
- Produces: consistent floating notifications and a source-level guard against reintroducing the retired navy/cyan/square-card theme.

- [ ] **Step 1: Write a failing legacy-theme guard and notification semantic test**

```ts
// frontend/src/styles/__tests__/legacy-theme.spec.ts
import { readdirSync, readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { expect, it } from 'vitest'

function sourceFiles(directory: string): string[] {
  return readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const path = `${directory}/${entry.name}`
    return entry.isDirectory() ? sourceFiles(path) : [path]
  })
}

it('contains no retired theme tokens or colored left-bar decoration', () => {
  const currentFile = fileURLToPath(import.meta.url)
  const sourceRoot = fileURLToPath(new URL('../..', import.meta.url))
  const files = sourceFiles(sourceRoot)
    .filter(file => file !== currentFile && /\.(vue|css)$/.test(file))
  const source = files.map(file => readFileSync(file, 'utf8')).join('\n')
  const retired = ['--' + 'navy', '--' + 'cyan', '#' + '12233f', '#' + '35c2cb']

  for (const token of retired) expect(source).not.toContain(token)
  expect(source.replace(/\s/g, '')).not.toContain('border-' + 'left:3pxsolid')
})
```

Add a toast test that expects `role="status"`, an accessible close label, and removal after `dismissAfterMs` even when reduced motion is mocked.

- [ ] **Step 2: Run the guard and notification tests and verify the new notification semantics fail**

Run: `corepack pnpm --dir frontend exec vitest run src/styles/__tests__/legacy-theme.spec.ts src/features/notifications/__tests__/NotificationToasts.spec.ts`

Expected: the new notification semantic test FAILS. The legacy-theme guard should already PASS after Tasks 1–10; if it fails, Step 2 prints the exact source files that must be corrected within this task.

- [ ] **Step 3: Migrate notifications and remove every reported legacy visual value**

Notification toasts must use a dark translucent floating pill, two-layer shadow, immediate press feedback, direct rendering for async items, and reduced-motion opacity-only exit. Remove retired navy/cyan variables, square 2–3px radii, colored left borders, and obsolete selectors after confirming no template references remain.

```vue
<article class="notification-toast" role="status" :aria-label="toast.title">
  <i class="notification-live-dot" aria-hidden="true" />
  <div><strong>{{ toast.title }}</strong><p>{{ toast.detail }}</p></div>
  <button type="button" aria-label="关闭提醒" @click="dismiss(toast.id)">×</button>
</article>
```

- [ ] **Step 4: Run all frontend static and unit verification**

Run:

```powershell
corepack pnpm --dir frontend exec vitest run
corepack pnpm --dir frontend typecheck
corepack pnpm --dir frontend build
rg -n --glob '*.vue' --glob '*.css' -- '--navy|--cyan|#12233f|#35c2cb|border-left:\s*3px' frontend/src
```

Expected: tests/typecheck/build PASS; `rg` returns no matches.

- [ ] **Step 5: Commit notification polish and legacy cleanup**

```powershell
git add frontend/src/features/notifications frontend/src/styles
git status --short
git diff --cached --check
git commit -m "style: complete Apple Design theme migration"
```

Before committing, verify `git status --short` contains only frontend UI files from this task and no deployment paths.

### Task 12: Add Route-Sheet and Responsive Browser Acceptance

**Files:**
- Modify: `frontend/playwright.config.ts`
- Modify: `frontend/tests/e2e/helpers.ts`
- Create: `frontend/tests/e2e/apple-ui-route-sheet.spec.ts`
- Modify: `frontend/tests/e2e/developer-task-flow.spec.ts`
- Modify: `frontend/tests/e2e/operator-call-complete.spec.ts`
- Modify: `frontend/tests/e2e/leader-roster-redistribute.spec.ts`

**Interfaces:**
- Produces: desktop and 390×844 mobile acceptance for route detail, direct refresh, back/close, responsive navigation, 44px targets, and no horizontal overflow.
- Consumes: existing deterministic seed, `apiCall`, `rolePage`, and completed Tasks 1–11.

- [ ] **Step 1: Write the route-sheet E2E acceptance test with deterministic task creation**

```ts
import { expect, test } from '@playwright/test'
import {
  apiCall, localDateTime, rolePage, todayInShanghai,
  type CreatedTask,
} from './helpers'

test('task detail supports direct URL, refresh, close fallback, and mobile bottom sheet', async ({ browser, baseURL }) => {
  const today = todayInShanghai()
  const created = await apiCall<CreatedTask>(baseURL!, 'dev1', 'POST', '/api/tasks', {
    category: 'VERSION_RELEASE',
    systemName: 'UI 验收系统',
    estimatedMinutes: 30,
    processNumber: `UI-${today}-001`,
    operationStart: localDateTime(today, '14:00'),
    operationEnd: localDateTime(today, '15:00'),
  })
  const developer = await rolePage(browser, 'dev1', { viewport: { width: 390, height: 844 } })
  await developer.page.goto(`/tasks/${created.id}`)
  const dialog = developer.page.getByRole('dialog', { name: /任务详情/ })
  await expect(dialog).toBeVisible()
  await expect(dialog).toHaveCSS('bottom', '0px')
  await developer.page.reload()
  await expect(dialog).toBeVisible()
  await developer.page.getByRole('button', { name: '关闭任务详情' }).click()
  await expect(developer.page).toHaveURL(/\/tasks$/)
  await developer.context.close()
})
```

- [ ] **Step 2: Extend `rolePage` and Playwright projects, then run the new acceptance test**

```ts
// helpers.ts — extend the existing Playwright import instead of adding a second import
import {
  expect, request,
  type Browser, type BrowserContextOptions, type Page,
} from '@playwright/test'

export async function rolePage(
  browser: Browser,
  user: E2eUser,
  options: BrowserContextOptions = {},
) {
  const context = await browser.newContext({ ...options, storageState: storageStatePath(user) })
  return { context, page: await context.newPage() }
}
```

Run: `corepack pnpm --dir frontend exec playwright test tests/e2e/apple-ui-route-sheet.spec.ts --project=chromium`

Expected at Task 12 execution time: PASS after selector-level corrections only. This task adds acceptance coverage and does not introduce product behavior; any product failure returns to the owning earlier task.

- [ ] **Step 3: Add complete desktop/mobile acceptance assertions and update existing selectors**

Add assertions for: list-row click updates `/tasks/:id`; browser Back closes the sheet and restores the prior task filters; Escape closes and restores row focus; desktop sheet is right anchored; mobile navigation exposes no more than four direct items; 320px/390px pages have `document.documentElement.scrollWidth === document.documentElement.clientWidth`; all visible buttons/links have at least a 44px hit-box; reduced-motion context still exposes immediate status feedback. Update old E2E locators only where markup changed, retaining their business assertions.

```ts
await expect.poll(() => page.evaluate(() => ({
  scrollWidth: document.documentElement.scrollWidth,
  clientWidth: document.documentElement.clientWidth,
}))).toEqual(expect.objectContaining({ scrollWidth: expect.any(Number) }))
expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
```

- [ ] **Step 4: Run the complete automated verification matrix**

Run:

```powershell
corepack pnpm --dir frontend exec vitest run
corepack pnpm --dir frontend typecheck
corepack pnpm --dir frontend build
corepack pnpm --dir frontend exec playwright test --project=chromium
```

Expected: all commands PASS with zero retries.

- [ ] **Step 5: Perform visual browser verification against the approved prototype**

Start the verified stack or E2E environment, then capture and inspect these routes at 1440×900, 1024×768, and 390×844: `/login`, `/workspace`, `/tasks`, `/tasks/:id`, `/tasks/new`, `/rosters`, `/people`, `/reports`, `/audit`, and `/403`. For every route confirm: correct background/surface hierarchy, one blue accent, no legacy navy/cyan areas, no permanent topbar divider, 44px controls, no clipped content, correct sheet direction, and readable 200% zoom. Repeat with Chromium emulation for reduced motion, reduced transparency, and increased contrast.

Save verification screenshots under `frontend/test-results/apple-ui-review/` and keep that generated directory untracked.

- [ ] **Step 6: Commit acceptance coverage**

```powershell
git add frontend/playwright.config.ts frontend/tests/e2e
git commit -m "test: cover Apple UI route sheet and responsive layout"
```

### Task 13: Final Regression and Completion Evidence

**Files:**
- Modify only if a verification failure identifies a concrete defect: the smallest relevant frontend source/test file.

**Interfaces:**
- Consumes: all previous tasks.
- Produces: a clean, evidence-backed handoff with no frontend regression and no accidental deployment-file staging.

- [ ] **Step 1: Verify the final diff scope**

Run:

```powershell
git status --short
git diff --name-only origin/main...HEAD
git diff --check origin/main...HEAD
```

Expected: committed changes are limited to the approved design spec, implementation plan if committed, and frontend UI/test files; the five pre-existing deployment changes remain uncommitted and separate.

- [ ] **Step 2: Run the full frontend verification one final time**

Run:

```powershell
corepack pnpm --dir frontend exec vitest run
corepack pnpm --dir frontend typecheck
corepack pnpm --dir frontend build
corepack pnpm --dir frontend exec playwright test --project=chromium
```

Expected: PASS.

- [ ] **Step 3: Record completion evidence**

Capture in the final handoff: commit list, unit-test file/test counts, typecheck result, build result, Playwright pass count, verified viewport list, media-preference checks, and the fact that deployment worktree changes were not staged or modified.

- [ ] **Step 4: Commit only a concrete final correction if Step 2 exposed one**

If no correction was needed, do not create an empty commit. If a correction was required:

```powershell
$uiFiles = @(git diff --name-only HEAD -- frontend/src frontend/tests/e2e frontend/playwright.config.ts)
if ($uiFiles.Count -eq 0) {
  Write-Output 'No final correction commit required'
  exit 0
}
git add -- $uiFiles
git diff --cached --check
git commit -m "fix: resolve final Apple UI regression"
```
