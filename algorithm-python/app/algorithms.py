import math
from typing import Any, Dict, List, Tuple

import numpy as np
import pandas as pd
from sklearn.ensemble import RandomForestRegressor
from sklearn.metrics import pairwise_distances, silhouette_score


def execute_algorithm(
    task_type: str,
    data: pd.DataFrame,
    metadata: Dict[str, Any],
    parameters: Dict[str, Any],
) -> Dict[str, Any]:
    handlers = {
        "PROFILE": profile_data,
        "FEATURE": extract_features,
        "CLUSTER": cluster_users,
        "FORECAST": forecast_load,
        "BASELINE": calculate_baselines,
        "POTENTIAL": calculate_potential,
    }
    handler = handlers.get(task_type.upper())
    if handler is None:
        raise ValueError(f"不支持的任务类型：{task_type}")
    result = handler(data, metadata, parameters)
    cluster_id = parameters.get("clusterId")
    if cluster_id is not None and task_type.upper() in {"FORECAST", "BASELINE", "POTENTIAL"}:
        result["clusterId"] = int(cluster_id)
    return result


def filter_cluster_data(
    data: pd.DataFrame,
    task_type: str,
    parameters: Dict[str, Any],
    upstream_results: Dict[str, Any],
) -> pd.DataFrame:
    if task_type.upper() not in {"FORECAST", "BASELINE", "POTENTIAL"}:
        return data
    cluster_id = parameters.get("clusterId")
    cluster_result = upstream_results.get("CLUSTER") if upstream_results else None
    if cluster_id is None or not cluster_result:
        raise ValueError("集群级分析缺少聚类编号或聚类结果")
    selected_ids = {
        str(member["sampleId"])
        for member in cluster_result.get("members", [])
        if int(member.get("clusterId", 0)) == int(cluster_id)
    }
    if not selected_ids:
        raise ValueError(f"聚类结果中不存在集群 {cluster_id}")
    if cluster_result.get("sampleKind") == "DAY":
        mask = data["timestamp"].dt.date.astype(str).isin(selected_ids)
    else:
        mask = data["user_id"].astype(str).isin(selected_ids)
    filtered = data.loc[mask].copy()
    if filtered.empty:
        raise ValueError(f"集群 {cluster_id} 没有可用于分析的负荷数据")
    return filtered


def profile_data(
    data: pd.DataFrame,
    metadata: Dict[str, Any],
    parameters: Dict[str, Any],
) -> Dict[str, Any]:
    del parameters
    expected_points = _expected_point_count(data, metadata["intervalMinutes"])
    missing_ratio = 0.0 if expected_points == 0 else max(0.0, 1 - len(data) / expected_points)
    load_values = data["load_kw"].astype(float)
    quality_score = max(0.0, 100.0 - missing_ratio * 70 - metadata["invalidRowCount"] * 0.01)

    return {
        "rowCount": int(len(data)),
        "userCount": int(data["user_id"].nunique()),
        "startTime": data["timestamp"].min().isoformat(),
        "endTime": data["timestamp"].max().isoformat(),
        "intervalMinutes": metadata["intervalMinutes"],
        "unit": "kW",
        "peakLoad": round(float(load_values.max()), 4),
        "valleyLoad": round(float(load_values.min()), 4),
        "averageLoad": round(float(load_values.mean()), 4),
        "totalEnergyKwh": round(float(load_values.sum() * metadata["intervalMinutes"] / 60), 4),
        "missingRatio": round(float(missing_ratio), 6),
        "duplicateCount": metadata["duplicateCount"],
        "invalidRowCount": metadata["invalidRowCount"],
        "qualityScore": round(quality_score, 2),
        "sourceFormat": metadata["sourceFormat"],
        "originalColumns": metadata["originalColumns"],
        "preview": _series_points(data.groupby("timestamp", as_index=False)["load_kw"].sum().tail(96)),
    }


def extract_features(
    data: pd.DataFrame,
    metadata: Dict[str, Any],
    parameters: Dict[str, Any],
) -> Dict[str, Any]:
    del parameters
    interval_hours = metadata["intervalMinutes"] / 60.0
    feature_rows = []
    for user_id, group in data.groupby("user_id"):
        group = group.sort_values("timestamp")
        values = group["load_kw"].astype(float)
        peak_index = values.idxmax()
        peak_time = group.loc[peak_index, "timestamp"]
        mean_load = float(values.mean())
        peak_load = float(values.max())
        feature_rows.append(
            {
                "userId": str(user_id),
                "peakLoad": round(peak_load, 4),
                "valleyLoad": round(float(values.min()), 4),
                "averageLoad": round(mean_load, 4),
                "p95Load": round(float(values.quantile(0.95)), 4),
                "peakValleyDifference": round(float(values.max() - values.min()), 4),
                "loadFactor": round(mean_load / peak_load, 4) if peak_load else 0.0,
                "energyKwh": round(float(values.sum() * interval_hours), 4),
                "peakTime": peak_time.isoformat(),
                "maximumRamp": round(float(values.diff().abs().max() or 0), 4),
                "coefficientOfVariation": round(float(values.std() / mean_load), 4) if mean_load else 0.0,
                "pointCount": int(len(values)),
            }
        )

    feature_rows.sort(key=lambda row: row["peakLoad"], reverse=True)
    return {
        "userCount": len(feature_rows),
        "intervalMinutes": metadata["intervalMinutes"],
        "features": feature_rows[:1000],
    }


def cluster_users(
    data: pd.DataFrame,
    metadata: Dict[str, Any],
    parameters: Dict[str, Any],
) -> Dict[str, Any]:
    matrix, sample_ids, sample_kind = _build_cluster_matrix(data, metadata["intervalMinutes"])
    normalized = _normalize_rows(matrix)
    sample_count = len(normalized)
    requested_clusters = int(parameters.get("clusterCount", 0) or 0)

    if sample_count == 1:
        labels = np.array([0])
        medoid_indices = [0]
        cluster_count = 1
        metrics = [{"clusterCount": 1, "silhouette": None}]
    elif sample_count == 2:
        labels = np.array([0, 1])
        medoid_indices = [0, 1]
        cluster_count = 2
        metrics = [{"clusterCount": 2, "silhouette": None}]
    else:
        maximum_clusters = min(6, sample_count - 1)
        candidates = [requested_clusters] if 2 <= requested_clusters <= maximum_clusters else list(
            range(2, maximum_clusters + 1)
        )
        evaluations = []
        results = {}
        for cluster_count_candidate in candidates:
            labels_candidate, medoids_candidate, distances = _pam(normalized, cluster_count_candidate)
            score = silhouette_score(distances, labels_candidate, metric="precomputed")
            evaluations.append(
                {
                    "clusterCount": cluster_count_candidate,
                    "silhouette": round(float(score), 5),
                }
            )
            results[cluster_count_candidate] = (labels_candidate, medoids_candidate)
        selected = max(evaluations, key=lambda item: item["silhouette"])["clusterCount"]
        labels, medoid_indices = results[selected]
        cluster_count = selected
        metrics = evaluations

    clusters = []
    members = []
    for cluster_id in range(cluster_count):
        indices = np.where(labels == cluster_id)[0]
        representative = matrix[medoid_indices[cluster_id]]
        clusters.append(
            {
                "clusterId": cluster_id + 1,
                "memberCount": int(len(indices)),
                "representativeCurve": [round(float(value), 4) for value in representative],
                "averageCurve": [round(float(value), 4) for value in matrix[indices].mean(axis=0)],
            }
        )
        members.extend(
            {
                "sampleId": str(sample_ids[index]),
                "clusterId": cluster_id + 1,
            }
            for index in indices
        )

    return {
        "sampleKind": sample_kind,
        "sampleCount": sample_count,
        "clusterCount": cluster_count,
        "intervalMinutes": metadata["intervalMinutes"],
        "metrics": metrics,
        "clusters": clusters,
        "members": members[:5000],
    }


def forecast_load(
    data: pd.DataFrame,
    metadata: Dict[str, Any],
    parameters: Dict[str, Any],
) -> Dict[str, Any]:
    series = _aggregate_series(data, metadata["intervalMinutes"])
    interval_minutes = metadata["intervalMinutes"]
    points_per_day = max(1, round(1440 / interval_minutes))
    horizon = max(1, min(int(parameters.get("forecastSteps", points_per_day)), points_per_day * 2))
    values = series.to_numpy(dtype=float)
    timestamps = series.index

    future_times = pd.date_range(
        start=timestamps[-1] + pd.Timedelta(minutes=interval_minutes),
        periods=horizon,
        freq=f"{interval_minutes}min",
    )
    future_values = _seasonal_profile_forecast(series, future_times, interval_minutes)

    validation_metrics = {"wmape": None, "mae": None, "rmse": None}
    residual_standard_deviation = float(np.nanstd(np.diff(values))) if len(values) > 2 else 0.0
    if len(values) >= points_per_day * 8:
        validation_actual = series.iloc[-points_per_day:]
        training_series = series.iloc[:-points_per_day]
        validation_prediction = np.asarray(
            _seasonal_profile_forecast(training_series, validation_actual.index, interval_minutes),
            dtype=float,
        )
        validation_values = validation_actual.to_numpy(dtype=float)
        validation_metrics = _regression_metrics(validation_values, validation_prediction)
        residual_standard_deviation = float(np.std(validation_values - validation_prediction))

    interval_width = 1.2816 * residual_standard_deviation
    forecast_points = [
        {
            "timestamp": timestamp.isoformat(),
            "p10": round(max(0.0, value - interval_width), 4),
            "p50": round(max(0.0, value), 4),
            "p90": round(max(0.0, value + interval_width), 4),
        }
        for timestamp, value in zip(future_times, future_values)
    ]

    history = pd.DataFrame({"timestamp": timestamps[-min(300, len(timestamps)) :], "load_kw": values[-300:]})
    return {
        "model": "WEEKDAY_SEASONAL_PROFILE",
        "intervalMinutes": interval_minutes,
        "forecastSteps": horizon,
        "metrics": validation_metrics,
        "history": _series_points(history),
        "forecast": forecast_points,
        "degraded": False,
        "degradationReason": None,
    }


def calculate_baselines(
    data: pd.DataFrame,
    metadata: Dict[str, Any],
    parameters: Dict[str, Any],
) -> Dict[str, Any]:
    del parameters
    pivot, interval_minutes = _daily_curves(data, metadata["intervalMinutes"])
    if pivot.empty:
        raise ValueError("没有足够的完整日负荷数据计算基线")
    pivot = pivot.tail(90)
    values = pivot.to_numpy(dtype=float)
    median_curve = np.median(values, axis=0)
    typical_index = int(np.argmin(np.linalg.norm(values - median_curve, axis=1)))

    curves = {
        "mean": values[-30:].mean(axis=0),
        "max": values[-15:].max(axis=0),
        "min": values[-15:].min(axis=0),
        "quantile30": np.quantile(values[-30:], 0.3, axis=0),
        "typical": values[typical_index],
    }
    return {
        "intervalMinutes": interval_minutes,
        "candidateDayCount": int(len(pivot)),
        "candidateDates": [str(date) for date in pivot.index[-30:]],
        "curves": {
            name: [round(float(value), 4) for value in curve]
            for name, curve in curves.items()
        },
    }


def calculate_potential(
    data: pd.DataFrame,
    metadata: Dict[str, Any],
    parameters: Dict[str, Any],
) -> Dict[str, Any]:
    upstream_results = parameters.get("_upstreamResults", {})
    forecast_result = upstream_results.get("FORECAST") or forecast_load(data, metadata, parameters)
    baseline_result = upstream_results.get("BASELINE") or calculate_baselines(data, metadata, parameters)
    baseline_type = str(parameters.get("baselineType", "typical"))
    baseline_curve = baseline_result["curves"].get(baseline_type)
    if baseline_curve is None:
        raise ValueError(f"不支持的基线类型：{baseline_type}")

    forecast_curve = [point["p50"] for point in forecast_result["forecast"]]
    repeated_baseline = [baseline_curve[index % len(baseline_curve)] for index in range(len(forecast_curve))]
    maximum_capacity = float(parameters.get("maximumAdjustableKw", float("inf")))
    interval_hours = metadata["intervalMinutes"] / 60.0
    down_curve = [min(max(forecast - baseline, 0.0), maximum_capacity) for forecast, baseline in zip(
        forecast_curve, repeated_baseline
    )]
    up_curve = [min(max(baseline - forecast, 0.0), maximum_capacity) for forecast, baseline in zip(
        forecast_curve, repeated_baseline
    )]

    points = []
    for index, forecast_point in enumerate(forecast_result["forecast"]):
        points.append(
            {
                "timestamp": forecast_point["timestamp"],
                "forecastKw": forecast_curve[index],
                "baselineKw": round(float(repeated_baseline[index]), 4),
                "downKw": round(float(down_curve[index]), 4),
                "upKw": round(float(up_curve[index]), 4),
            }
        )

    return {
        "baselineType": baseline_type,
        "intervalMinutes": metadata["intervalMinutes"],
        "maximumDownKw": round(float(max(down_curve, default=0.0)), 4),
        "maximumUpKw": round(float(max(up_curve, default=0.0)), 4),
        "downEnergyKwh": round(float(sum(down_curve) * interval_hours), 4),
        "upEnergyKwh": round(float(sum(up_curve) * interval_hours), 4),
        "forecastModel": forecast_result["model"],
        "points": points,
    }


def _expected_point_count(data: pd.DataFrame, interval_minutes: int) -> int:
    if data.empty:
        return 0
    time_span_minutes = (data["timestamp"].max() - data["timestamp"].min()).total_seconds() / 60
    points_per_user = int(time_span_minutes / interval_minutes) + 1
    return points_per_user * data["user_id"].nunique()


def _build_cluster_matrix(data: pd.DataFrame, interval_minutes: int) -> Tuple[np.ndarray, List[str], str]:
    working = data.copy()
    points_per_day = max(1, round(1440 / interval_minutes))
    working["slot"] = ((working["timestamp"].dt.hour * 60 + working["timestamp"].dt.minute) / interval_minutes).astype(int)
    user_count = working["user_id"].nunique()

    if user_count < 2:
        raise ValueError("用户聚类至少需要两个不同的用电单元，请检查数据文件和用户编号字段映射")
    pivot = working.pivot_table(index="user_id", columns="slot", values="load_kw", aggfunc="mean")
    sample_kind = "USER"
    pivot = pivot.reindex(columns=range(points_per_day)).interpolate(axis=1, limit_direction="both")
    pivot = pivot.dropna(axis=0, how="any")
    if pivot.empty:
        raise ValueError("没有足够的完整曲线用于聚类")
    return pivot.to_numpy(dtype=float), [str(index) for index in pivot.index], sample_kind


def _normalize_rows(matrix: np.ndarray) -> np.ndarray:
    minimum = matrix.min(axis=1, keepdims=True)
    maximum = matrix.max(axis=1, keepdims=True)
    scale = np.where(maximum - minimum == 0, 1.0, maximum - minimum)
    return (matrix - minimum) / scale


def _pam(matrix: np.ndarray, cluster_count: int) -> Tuple[np.ndarray, List[int], np.ndarray]:
    distances = pairwise_distances(matrix, metric="manhattan")
    medoids = [int(np.argmin(distances.sum(axis=1)))]
    while len(medoids) < cluster_count:
        nearest_distance = distances[:, medoids].min(axis=1)
        nearest_distance[medoids] = -1
        medoids.append(int(np.argmax(nearest_distance)))

    labels = np.zeros(len(matrix), dtype=int)
    for _ in range(30):
        labels = np.argmin(distances[:, medoids], axis=1)
        new_medoids = []
        for cluster_id in range(cluster_count):
            members = np.where(labels == cluster_id)[0]
            if len(members) == 0:
                new_medoids.append(medoids[cluster_id])
                continue
            local_distances = distances[np.ix_(members, members)]
            new_medoids.append(int(members[np.argmin(local_distances.sum(axis=1))]))
        if new_medoids == medoids:
            break
        medoids = new_medoids
    return labels, medoids, distances


def _aggregate_series(data: pd.DataFrame, interval_minutes: int) -> pd.Series:
    aggregated = data.groupby("timestamp")["load_kw"].sum().sort_index()
    full_index = pd.date_range(aggregated.index.min(), aggregated.index.max(), freq=f"{interval_minutes}min")
    return aggregated.reindex(full_index).interpolate(limit_direction="both")


def _seasonal_profile_forecast(
    history: pd.Series,
    future_times: pd.DatetimeIndex,
    interval_minutes: int,
) -> List[float]:
    if history.empty:
        return []
    history = history.sort_index()
    history_slots = (history.index.hour * 60 + history.index.minute) // interval_minutes
    recent_window = max(1, round(7 * 1440 / interval_minutes))
    recent_mean = float(history.iloc[-recent_window:].mean())
    previous = history.iloc[-recent_window * 2 : -recent_window]
    trend_factor = recent_mean / float(previous.mean()) if not previous.empty and float(previous.mean()) else 1.0
    trend_factor = min(1.08, max(0.92, trend_factor))

    predictions = []
    for timestamp in future_times:
        slot = (timestamp.hour * 60 + timestamp.minute) // interval_minutes
        same_weekday = history[(history_slots == slot) & (history.index.dayofweek == timestamp.dayofweek)].tail(8)
        candidates = same_weekday if len(same_weekday) >= 2 else history[history_slots == slot].tail(14)
        if candidates.empty:
            prediction = float(history.iloc[-1])
        else:
            weights = np.linspace(1.0, 2.0, len(candidates))
            prediction = float(np.average(candidates.to_numpy(dtype=float), weights=weights))
        predictions.append(max(0.0, prediction * trend_factor))
    return predictions


def _available_lags(value_count: int, points_per_day: int) -> List[int]:
    candidates = [1, 2, 4, points_per_day, points_per_day * 7]
    available = sorted({lag for lag in candidates if lag < value_count // 2})
    return available or [1]


def _supervised_data(
    values: np.ndarray,
    timestamps: pd.DatetimeIndex,
    lags: List[int],
) -> Tuple[np.ndarray, np.ndarray, np.ndarray]:
    maximum_lag = max(lags)
    features = []
    targets = []
    target_indices = []
    for index in range(maximum_lag, len(values)):
        features.append(_feature_vector(values, index, timestamps[index], lags))
        targets.append(values[index])
        target_indices.append(index)
    return np.asarray(features), np.asarray(targets), np.asarray(target_indices)


def _feature_vector(values: np.ndarray, index: int, timestamp: pd.Timestamp, lags: List[int]) -> List[float]:
    vector = [float(values[index - lag]) for lag in lags]
    hour_angle = 2 * math.pi * (timestamp.hour * 60 + timestamp.minute) / 1440
    week_angle = 2 * math.pi * timestamp.dayofweek / 7
    vector.extend([math.sin(hour_angle), math.cos(hour_angle), math.sin(week_angle), math.cos(week_angle)])
    return vector


def _recursive_forecast(
    values: np.ndarray,
    last_timestamp: pd.Timestamp,
    horizon: int,
    interval_minutes: int,
    points_per_day: int,
    lags: List[int],
    selected_model: str,
    model: RandomForestRegressor,
) -> List[float]:
    history = list(values.astype(float))
    predictions = []
    for step in range(1, horizon + 1):
        timestamp = last_timestamp + pd.Timedelta(minutes=interval_minutes * step)
        if selected_model == "RANDOM_FOREST" and model is not None:
            vector = _feature_vector(np.asarray(history), len(history), timestamp, lags)
            prediction = float(model.predict([vector])[0])
        else:
            seasonal_index = len(history) - points_per_day
            prediction = history[seasonal_index] if seasonal_index >= 0 else history[-1]
        history.append(max(0.0, prediction))
        predictions.append(max(0.0, prediction))
    return predictions


def _regression_metrics(actual: np.ndarray, predicted: np.ndarray) -> Dict[str, float]:
    errors = actual - predicted
    return {
        "wmape": round(_wmape(actual, predicted), 6),
        "mae": round(float(np.mean(np.abs(errors))), 4),
        "rmse": round(float(np.sqrt(np.mean(errors**2))), 4),
    }


def _wmape(actual: np.ndarray, predicted: np.ndarray) -> float:
    denominator = float(np.sum(np.abs(actual)))
    return float(np.sum(np.abs(actual - predicted)) / denominator) if denominator else 0.0


def _daily_curves(data: pd.DataFrame, interval_minutes: int) -> Tuple[pd.DataFrame, int]:
    aggregate = data.groupby("timestamp", as_index=False)["load_kw"].sum()
    aggregate["date"] = aggregate["timestamp"].dt.date.astype(str)
    aggregate["slot"] = (
        (aggregate["timestamp"].dt.hour * 60 + aggregate["timestamp"].dt.minute) / interval_minutes
    ).astype(int)
    points_per_day = max(1, round(1440 / interval_minutes))
    pivot = aggregate.pivot_table(index="date", columns="slot", values="load_kw", aggfunc="mean")
    pivot = pivot.reindex(columns=range(points_per_day))
    pivot = pivot[pivot.notna().mean(axis=1) >= 0.8]
    pivot = pivot.interpolate(axis=1, limit_direction="both").dropna(axis=0, how="any")
    return pivot, interval_minutes


def _series_points(data: pd.DataFrame) -> List[Dict[str, Any]]:
    timestamp_column = "timestamp" if "timestamp" in data.columns else data.columns[0]
    value_column = "load_kw" if "load_kw" in data.columns else data.columns[-1]
    return [
        {
            "timestamp": pd.Timestamp(row[timestamp_column]).isoformat(),
            "value": round(float(row[value_column]), 4),
        }
        for _, row in data.iterrows()
    ]
