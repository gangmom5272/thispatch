import { createBrowserRouter, Navigate } from "react-router"
import { AppShell } from "@/components/layout/AppShell"
import LoginPage from "@/pages/login/LoginPage"
import NotFound from "@/pages/NotFound"
import { PlaceholderPage } from "@/pages/PlaceholderPage"
import SignupPage from "@/pages/signup/SignupPage"
import {
  DEFAULT_GAME_DETAIL_TAB,
  GAME_DETAIL_MAIN_TABS,
  GAME_DETAIL_TAB_LABELS,
  paths,
  routeSegment,
} from "@/router/paths"
import GameDetailPage from "@/pages/GameDetail/GameDetailPage"
import { TabPlaceholder } from "@/pages/GameDetail/components/TabPlaceholder"
import GameListPage from "@/pages/GameList/GameListPage"
import PlanStructurePage from "@/pages/PlanStructure/PlanStructurePage"

export const router = createBrowserRouter([
  {
    path: paths.home,
    element: <AppShell />,
    children: [
      {
        index: true,
        element: <PlaceholderPage title="홈" />,
      },
      {
        path: routeSegment(paths.games),
        element: <GameListPage />,
      },
      {
        path: routeSegment(paths.gameDetailPattern),
        element: <GameDetailPage />,
        children: [
          {
            index: true,
            element: <Navigate to={DEFAULT_GAME_DETAIL_TAB} replace />,
          },
          ...GAME_DETAIL_MAIN_TABS.map((tab) => ({
            path: tab,
            element: <TabPlaceholder title={GAME_DETAIL_TAB_LABELS[tab]} />,
          })),
        ],
      },
      {
        path: routeSegment(paths.gamePlanPattern),
        element: <PlanStructurePage />,
      },
      {
        path: routeSegment(paths.gameCasesPattern),
        element: <PlaceholderPage title="유사 사례 검색" />,
      },
      {
        path: routeSegment(paths.methodology),
        element: <PlaceholderPage title="방법론" />,
      },
      {
        path: routeSegment(paths.login),
        element: <LoginPage />,
      },
      {
        path: routeSegment(paths.signup),
        element: <SignupPage />,
      },
      {
        path: "*",
        element: <NotFound />,
      },
    ],
  },
])
