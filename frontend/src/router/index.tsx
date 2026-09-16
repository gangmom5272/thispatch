import { createBrowserRouter, Navigate } from "react-router"
import { AppShell } from "@/components/layout/AppShell"
import LoginPage from "@/pages/login/LoginPage"
import NotFound from "@/pages/NotFound"
import { PlaceholderPage } from "@/pages/PlaceholderPage"
import SignupPage from "@/pages/signup/SignupPage"
import {
  DEFAULT_GAME_DETAIL_TAB,
  GAME_DETAIL_TABS,
  GAME_DETAIL_MAIN_TABS,
  GAME_DETAIL_TAB_LABELS,
  paths,
  routeSegment,
} from "@/router/paths"
import GameDetailPage from "@/pages/GameDetail/GameDetailPage"
import PlaytimeTopicsPage from "@/pages/GameDetail/PlaytimeTopics/PlaytimeTopicsPage"
import ReactionTrendsPage from "@/pages/GameDetail/ReactionTrends/ReactionTrendsPage"
import ReviewsPage from "@/pages/GameDetail/Reviews/ReviewsPage"
import { TabPlaceholder } from "@/pages/GameDetail/components/TabPlaceholder"
import GameListPage from "@/pages/GameList/GameListPage"
import LanguageAnalysisPage from "@/pages/LanguageAnalysis/LanguageAnalysisPage"
import PlanStructurePage from "@/pages/PlanStructure/PlanStructurePage"
import CaseSearchPage from "@/pages/CaseSearch/CaseSearchPage"
import CaseDetailPage from "@/pages/CaseDetail/CaseDetailPage"

export const router = createBrowserRouter([
  {
    path: paths.home,
    element: <AppShell />,
    children: [
      {
        index: true,
        element: <GameListPage />,
      },
      {
        path: "games",
        element: <Navigate to={paths.home} replace />,
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
            element:
              tab === GAME_DETAIL_TABS.reactionTrends ? (
                <ReactionTrendsPage />
              ) : tab === GAME_DETAIL_TABS.playtimeTopics ? (
                <PlaytimeTopicsPage />
              ) : tab === GAME_DETAIL_TABS.reviews ? (
                <ReviewsPage />
              ) : tab === GAME_DETAIL_TABS.languageAnalysis ? (
                <LanguageAnalysisPage />
              ) : (
                <TabPlaceholder title={GAME_DETAIL_TAB_LABELS[tab]} />
              ),
          })),
        ],
      },
      {
        path: routeSegment(paths.gamePlanPattern),
        element: <PlanStructurePage />,
      },
      {
        path: routeSegment(paths.gameCasesPattern),
        element: <CaseSearchPage />,
      },
      {
        path: routeSegment(paths.gameCaseDetailPattern),
        element: <CaseDetailPage />,
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
