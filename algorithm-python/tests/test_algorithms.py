import unittest

import pandas as pd

from app.algorithms import (
    calculate_baselines,
    calculate_potential,
    cluster_users,
    extract_features,
    filter_cluster_data,
    forecast_load,
    profile_data,
)
from app.data_loader import normalize_load_data


def build_long_source(days: int = 20, users: int = 3) -> pd.DataFrame:
    rows = []
    timestamps = pd.date_range("2026-01-01", periods=days * 96, freq="15min")
    for user_index in range(users):
        for point_index, timestamp in enumerate(timestamps):
            slot = point_index % 96
            load = 50 + user_index * 30 + 20 * abs(48 - slot) / 48
            rows.append(
                {
                    "user_id": f"U{user_index + 1:03d}",
                    "timestamp": timestamp,
                    "load_kw": load,
                }
            )
    return pd.DataFrame(rows)


class AlgorithmTestCase(unittest.TestCase):
    def test_normalize_and_profile(self) -> None:
        source = build_long_source(days=3, users=2)
        normalized, metadata = normalize_load_data(source, {"unit": "kW"})
        result = profile_data(normalized, metadata, {})
        self.assertEqual(result["userCount"], 2)
        self.assertEqual(result["rowCount"], 3 * 96 * 2)
        self.assertEqual(result["intervalMinutes"], 15)

    def test_feature_and_cluster(self) -> None:
        normalized, metadata = normalize_load_data(build_long_source(), {"unit": "kW"})
        features = extract_features(normalized, metadata, {})
        clusters = cluster_users(normalized, metadata, {"clusterCount": 2})
        self.assertEqual(features["userCount"], 3)
        self.assertEqual(clusters["clusterCount"], 2)
        self.assertEqual(len(clusters["members"]), 3)

    def test_cluster_with_two_users(self) -> None:
        normalized, metadata = normalize_load_data(
            build_long_source(days=3, users=2),
            {"unit": "kW"},
        )
        clusters = cluster_users(normalized, metadata, {"clusterCount": 2})
        self.assertEqual(clusters["clusterCount"], 2)
        self.assertEqual(len(clusters["members"]), 2)
        self.assertIsNone(clusters["metrics"][0]["silhouette"])

    def test_cluster_rejects_single_user_instead_of_clustering_days(self) -> None:
        normalized, metadata = normalize_load_data(build_long_source(days=10, users=1), {"unit": "kW"})
        with self.assertRaisesRegex(ValueError, "至少需要两个"):
            cluster_users(normalized, metadata, {"clusterCount": 2})

    def test_forecast_baseline_and_potential(self) -> None:
        normalized, metadata = normalize_load_data(build_long_source(days=25), {"unit": "kW"})
        forecast = forecast_load(normalized, metadata, {"forecastSteps": 24})
        baselines = calculate_baselines(normalized, metadata, {})
        potential = calculate_potential(
            normalized,
            metadata,
            {"forecastSteps": 24, "baselineType": "typical"},
        )
        self.assertEqual(len(forecast["forecast"]), 24)
        predicted = [point["p50"] for point in forecast["forecast"]]
        self.assertGreater(max(predicted) - min(predicted), 5)
        self.assertEqual(forecast["model"], "WEEKDAY_SEASONAL_PROFILE")
        self.assertEqual(len(baselines["curves"]["typical"]), 96)
        self.assertGreaterEqual(potential["downEnergyKwh"], 0)
        self.assertGreaterEqual(potential["upEnergyKwh"], 0)

    def test_cluster_filter_keeps_only_selected_users(self) -> None:
        normalized, _ = normalize_load_data(build_long_source(days=3, users=3), {"unit": "kW"})
        cluster_result = {
            "sampleKind": "USER",
            "members": [
                {"sampleId": "U001", "clusterId": 1},
                {"sampleId": "U002", "clusterId": 2},
                {"sampleId": "U003", "clusterId": 2},
            ],
        }
        filtered = filter_cluster_data(
            normalized,
            "FORECAST",
            {"clusterId": 2},
            {"CLUSTER": cluster_result},
        )
        self.assertEqual(set(filtered["user_id"].unique()), {"U002", "U003"})

    def test_potential_reuses_upstream_forecast_and_baseline(self) -> None:
        normalized, metadata = normalize_load_data(build_long_source(days=3, users=1), {"unit": "kW"})
        upstream_forecast = {
            "model": "UPSTREAM_MODEL",
            "forecast": [{"timestamp": "2026-02-01T00:00:00", "p50": 80.0}],
        }
        upstream_baseline = {"curves": {"typical": [50.0]}}
        result = calculate_potential(
            normalized,
            metadata,
            {
                "baselineType": "typical",
                "_upstreamResults": {
                    "FORECAST": upstream_forecast,
                    "BASELINE": upstream_baseline,
                },
            },
        )
        self.assertEqual(result["forecastModel"], "UPSTREAM_MODEL")
        self.assertEqual(result["points"][0]["downKw"], 30.0)


if __name__ == "__main__":
    unittest.main()
