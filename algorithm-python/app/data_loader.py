import json
import re
from pathlib import Path
from typing import Any, Dict, Optional, Tuple

import numpy as np
import pandas as pd


USER_CANDIDATES = [
    "user_id",
    "userid",
    "cons_no",
    "consno",
    "meter_id",
    "meterid",
    "用户编号",
    "用电单元编码",
]
TIME_CANDIDATES = ["timestamp", "datetime", "date_time", "time", "采集时间", "采集时刻", "时间"]
DATE_CANDIDATES = ["data_date", "date", "day", "数据日期", "日期"]
VALUE_CANDIDATES = ["load_kw", "load", "value", "power", "active_power", "有功功率_mw", "负荷", "功率"]


def parse_json(value: Optional[str]) -> Dict[str, Any]:
    if value is None or value.strip() == "":
        return {}
    parsed = json.loads(value)
    return parsed if isinstance(parsed, dict) else {}


def read_source(path: Path) -> pd.DataFrame:
    suffix = path.suffix.lower()
    if suffix == ".csv":
        try:
            return pd.read_csv(path, encoding="utf-8-sig")
        except UnicodeDecodeError:
            return pd.read_csv(path, encoding="gb18030")
    if suffix in {".xlsx", ".xls"}:
        return pd.read_excel(path)
    raise ValueError("只支持CSV和Excel数据文件")


def normalize_load_data(source: pd.DataFrame, mapping: Dict[str, Any]) -> Tuple[pd.DataFrame, Dict[str, Any]]:
    if source.empty:
        raise ValueError("数据文件没有可用行")

    original_columns = [str(column) for column in source.columns]
    source = source.copy()
    source.columns = [str(column).strip() for column in source.columns]
    column_lookup = {column.lower(): column for column in source.columns}

    wide_columns = sorted(
        [column for column in source.columns if re.fullmatch(r"p\d+", column.lower())],
        key=lambda column: int(re.findall(r"\d+", column)[0]),
    )
    if wide_columns:
        normalized, interval_minutes = _normalize_wide(source, column_lookup, wide_columns, mapping)
        source_format = "WIDE"
    else:
        normalized, interval_minutes = _normalize_long(source, column_lookup, mapping)
        source_format = "LONG"

    before_drop = len(normalized)
    normalized["timestamp"] = pd.to_datetime(normalized["timestamp"], errors="coerce")
    normalized["load_kw"] = pd.to_numeric(normalized["load_kw"], errors="coerce")
    normalized["user_id"] = normalized["user_id"].fillna("UNKNOWN").astype(str)
    normalized = normalized.dropna(subset=["timestamp", "load_kw"])
    normalized = normalized.sort_values(["user_id", "timestamp"])

    duplicate_count = int(normalized.duplicated(["user_id", "timestamp"]).sum())
    normalized = (
        normalized.groupby(["user_id", "timestamp"], as_index=False)["load_kw"].mean().sort_values("timestamp")
    )
    normalized["quality_flag"] = "ORIGINAL"

    unit = str(mapping.get("unit", "kW")).lower()
    if unit == "mw":
        normalized["load_kw"] = normalized["load_kw"] * 1000.0
    elif unit == "kwh":
        normalized["load_kw"] = normalized["load_kw"] / (interval_minutes / 60.0)
    elif unit not in {"kw", ""}:
        raise ValueError(f"暂不支持的负荷单位：{mapping.get('unit')}")

    metadata = {
        "sourceFormat": source_format,
        "originalColumns": original_columns,
        "sourceRowCount": int(len(source)),
        "normalizedRowCount": int(len(normalized)),
        "invalidRowCount": int(before_drop - len(normalized)),
        "duplicateCount": duplicate_count,
        "intervalMinutes": int(interval_minutes),
        "unit": "kW",
    }
    return normalized.reset_index(drop=True), metadata


def _normalize_wide(
    source: pd.DataFrame,
    column_lookup: Dict[str, str],
    point_columns: list,
    mapping: Dict[str, Any],
) -> Tuple[pd.DataFrame, int]:
    date_column = _resolve_column(mapping.get("dateColumn"), column_lookup, DATE_CANDIDATES)
    if date_column is None:
        raise ValueError("宽表数据必须包含日期字段")
    user_column = _resolve_column(mapping.get("userColumn"), column_lookup, USER_CANDIDATES)
    interval_minutes = int(mapping.get("intervalMinutes") or round(1440 / len(point_columns)))

    id_columns = [date_column]
    if user_column:
        id_columns.append(user_column)
    melted = source.melt(
        id_vars=id_columns,
        value_vars=point_columns,
        var_name="point_column",
        value_name="load_kw",
    )
    point_lookup = {column: index for index, column in enumerate(point_columns)}
    melted["point_index"] = melted["point_column"].map(point_lookup)
    melted["timestamp"] = pd.to_datetime(melted[date_column], errors="coerce") + pd.to_timedelta(
        melted["point_index"] * interval_minutes,
        unit="minute",
    )
    melted["user_id"] = melted[user_column] if user_column else "AGGREGATE"
    return melted[["user_id", "timestamp", "load_kw"]], interval_minutes


def _normalize_long(
    source: pd.DataFrame,
    column_lookup: Dict[str, str],
    mapping: Dict[str, Any],
) -> Tuple[pd.DataFrame, int]:
    time_column = _resolve_column(mapping.get("timeColumn"), column_lookup, TIME_CANDIDATES)
    value_column = _resolve_column(mapping.get("valueColumn"), column_lookup, VALUE_CANDIDATES)
    user_column = _resolve_column(mapping.get("userColumn"), column_lookup, USER_CANDIDATES)
    if time_column is None or value_column is None:
        raise ValueError("长表数据必须包含时间字段和负荷值字段，请先配置字段映射")

    normalized = pd.DataFrame(
        {
            "user_id": source[user_column] if user_column else "AGGREGATE",
            "timestamp": source[time_column],
            "load_kw": source[value_column],
        }
    )
    parsed_time = (
        pd.to_datetime(normalized["timestamp"], errors="coerce")
        .dropna()
        .drop_duplicates()
        .sort_values()
    )
    differences = parsed_time.diff().dropna().dt.total_seconds() / 60
    differences = differences[differences > 0]
    interval_minutes = int(mapping.get("intervalMinutes") or (differences.median() if not differences.empty else 15))
    return normalized, max(1, interval_minutes)


def _resolve_column(
    configured: Optional[str],
    column_lookup: Dict[str, str],
    candidates: list,
) -> Optional[str]:
    if configured and configured.lower() in column_lookup:
        return column_lookup[configured.lower()]
    for candidate in candidates:
        if candidate.lower() in column_lookup:
            return column_lookup[candidate.lower()]
    return None
