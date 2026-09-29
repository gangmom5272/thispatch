import type { ComponentType } from "react"
import { createBrowserRouter, Navigate } from "react-router"
import { AppShell } from "@/components/layout/AppShell"
import { RequireAuth } from "@/components/layout/RequireAuth"
import { RouteErrorBoundary } from "@/components/layout/RouteErrorBoundary"
import NotFound from "@/pages/NotFound"
import { PlaceholderPage } from "@/pages/PlaceholderPage"
import {
  DEFAULT_GAME_DETAIL_TAB,
  GAME_DETAIL_TABS,
  GAME_DETAIL_MAIN_TABS,
  GAME_DETAIL_TAB_LABELS,
  paths,
  routeSegment,
  type GameDetailTab,
} from "@/router/paths"
import { TabPlaceholder } from "@/pages/GameDetail/components/TabPlaceholder"

type PageModule = { default: ComponentType }

/** 페이지 모듈을 별도 청크로 분리해, 해당 라우트에 진입할 때 내려받는다. */
function lazyPage(importPage: () => Promise<PageModule>) {
  return async () => ({ Component: (await importPage()).default })
}

const gameDetailTabPages: Partial<Record<GameDetailTab, () => Promise<PageModule>>> = {
  [GAME_DETAIL_TABS.reactionTrends]: () =>
    import("@/pages/GameDetail/ReactionTrends/ReactionTrendsPage"),
  [GAME_DETAIL_TABS.playtimeTopics]: () =>
    import("@/pages/GameDetail/PlaytimeTopics/PlaytimeTopicsPage"),
  [GAME_DETAIL_TABS.reviews]: () => import("@/pages/GameDetail/Reviews/ReviewsPage"),
  [GAME_DETAIL_TABS.languageAnalysis]: () =>
    import("@/pages/GameDetail/LanguageAnalysis/LanguageAnalysisPage"),
}

export const router = createBrowserRouter([
  {
    path: paths.home,
    element: <AppShell />,
    children: [
      {
        errorElement: <RouteErrorBoundary />,
        hydrateFallbackElement: <main aria-busy="true" />,
        children: [
          {
            index: true,
            lazy: lazyPage(() => import("@/pages/Landing/LandingPage")),
          },
          {
            element: <RequireAuth />,
            children: [
              {
                path: routeSegment(paths.games),
                lazy: lazyPage(() => import("@/pages/GameList/GameListPage")),
              },
              {
                path: routeSegment(paths.gameDetailPattern),
                lazy: lazyPage(() => import("@/pages/GameDetail/GameDetailPage")),
                children: [
                  {
                    index: true,
                    element: <Navigate to={DEFAULT_GAME_DETAIL_TAB} replace />,
                  },
                  ...GAME_DETAIL_MAIN_TABS.map((tab) => {
                    const importPage = gameDetailTabPages[tab]
                    return importPage
                      ? { path: tab, lazy: lazyPage(importPage) }
                      : {
                          path: tab,
                          element: <TabPlaceholder title={GAME_DETAIL_TAB_LABELS[tab]} />,
                        }
                  }),
                ],
              },
              {
                path: routeSegment(paths.gamePlanPattern),
                lazy: lazyPage(() => import("@/pages/PlanStructure/PlanStructurePage")),
              },
              {
                path: routeSegment(paths.gameCasesPattern),
                lazy: lazyPage(() => import("@/pages/CaseSearch/CaseSearchPage")),
              },
              {
                path: routeSegment(paths.gameCaseDetailPattern),
                lazy: lazyPage(() => import("@/pages/CaseDetail/CaseDetailPage")),
              },
              {
                path: routeSegment(paths.myGames),
                lazy: lazyPage(() => import("@/pages/MyGames/MyGamesPage")),
              },
              {
                path: routeSegment(paths.myPage),
                lazy: lazyPage(() => import("@/pages/MyPage/MyPage")),
              },
              {
                path: routeSegment(paths.plans),
                lazy: lazyPage(() => import("@/pages/Plans/PlansPage")),
              },
            ],
          },
          {
            path: routeSegment(paths.methodology),
            element: <PlaceholderPage title="방법론" />,
          },
          {
            path: routeSegment(paths.login),
            lazy: lazyPage(() => import("@/pages/login/LoginPage")),
          },
          {
            path: routeSegment(paths.signup),
            lazy: lazyPage(() => import("@/pages/signup/SignupPage")),
          },
          {
            path: "*",
            element: <NotFound />,
          },
        ],
      },
    ],
  },
])
