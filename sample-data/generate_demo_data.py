import argparse
import csv
import math
import random
from datetime import datetime, timedelta
from pathlib import Path


OUTPUT_DIR = Path(__file__).resolve().parent
START = datetime(2026, 1, 1)


def gaussian(hour: float, center: float, width: float) -> float:
    distance = min(abs(hour - center), 24 - abs(hour - center))
    return math.exp(-0.5 * (distance / width) ** 2)


def load_value(unit_index: int, timestamp: datetime, interval_index: int) -> float:
    archetype = unit_index % 4
    scale = 0.78 + (unit_index % 7) * 0.085
    base = (32, 55, 90, 145)[archetype] * scale
    hour = timestamp.hour + timestamp.minute / 60
    weekend = timestamp.weekday() >= 5

    if archetype == 0:  # 居民：早晚双峰
        shape = 26 * gaussian(hour, 7.5, 1.5) + 58 * gaussian(hour, 19.5, 2.2)
        factor = 1.10 if weekend else 1.0
    elif archetype == 1:  # 办公：工作日白天高峰
        daytime = 1 / (1 + math.exp(-(hour - 7.8) * 2)) - 1 / (1 + math.exp(-(hour - 18.2) * 2))
        shape = 92 * daytime + 12 * gaussian(hour, 13.5, 3.0)
        factor = 0.38 if weekend else 1.0
    elif archetype == 2:  # 商业：午后至晚间高峰
        shape = 45 * gaussian(hour, 12.5, 3.0) + 72 * gaussian(hour, 18.5, 3.2)
        factor = 1.08 if weekend else 1.0
    else:  # 工业：班次平台并带午间回落
        shift = 1 / (1 + math.exp(-(hour - 6.5) * 2)) - 1 / (1 + math.exp(-(hour - 22.0) * 2))
        shape = 105 * shift - 18 * gaussian(hour, 12.0, 0.9)
        factor = 0.82 if weekend else 1.0

    day_index = (timestamp.date() - START.date()).days
    weekly = 1 + 0.025 * math.sin(day_index * 2 * math.pi / 7 + unit_index)
    trend = 1 + day_index * (0.0007 + (unit_index % 3) * 0.00015)
    rng = random.Random(unit_index * 10_000_000 + day_index * 1000 + interval_index)
    noise = rng.gauss(0, max(0.8, base * 0.012))
    return max(1.0, (base + shape * scale * factor) * weekly * trend + noise)


def generate_standard_long(path: Path, users: int = 24, days: int = 45) -> None:
    with path.open("w", newline="", encoding="utf-8-sig") as stream:
        writer = csv.writer(stream)
        writer.writerow(("user_id", "timestamp", "load_kw"))
        for day in range(days):
            for slot in range(96):
                timestamp = START + timedelta(days=day, minutes=slot * 15)
                for unit_index in range(users):
                    writer.writerow(
                        (
                            f"UNIT_{unit_index + 1:03d}",
                            timestamp.isoformat(sep=" "),
                            round(load_value(unit_index, timestamp, slot), 4),
                        )
                    )


def generate_custom_long(path: Path, users: int = 18, days: int = 42) -> None:
    with path.open("w", newline="", encoding="utf-8-sig") as stream:
        writer = csv.writer(stream)
        writer.writerow(("用电单元编码", "采集时刻", "有功功率_MW"))
        for day in range(days):
            for slot in range(48):
                timestamp = START + timedelta(days=day, minutes=slot * 30)
                for unit_index in range(users):
                    kw = load_value(unit_index + 30, timestamp, slot)
                    writer.writerow((f"CUSTOMER_{unit_index + 1:03d}", timestamp.isoformat(sep=" "), round(kw / 1000, 6)))


def generate_wide(path: Path, users: int = 16, days: int = 35) -> None:
    with path.open("w", newline="", encoding="utf-8-sig") as stream:
        writer = csv.writer(stream)
        writer.writerow(("user_id", "data_date", *(f"p{index + 1}" for index in range(96))))
        for day in range(days):
            date = (START + timedelta(days=day)).date()
            for unit_index in range(users):
                curve = []
                for slot in range(96):
                    timestamp = START + timedelta(days=day, minutes=slot * 15)
                    curve.append(round(load_value(unit_index + 60, timestamp, slot), 4))
                writer.writerow((f"METER_{unit_index + 1:03d}", date.isoformat(), *curve))


def main() -> None:
    parser = argparse.ArgumentParser(description="生成包含多个用电单元的 LoadFlex 测试数据")
    parser.add_argument("--output", help="仅生成一份标准长表到指定路径，供端到端脚本使用")
    parser.add_argument("--days", type=int, default=30)
    parser.add_argument("--users", type=int, default=16)
    arguments = parser.parse_args()
    if arguments.output:
        output = Path(arguments.output).resolve()
        output.parent.mkdir(parents=True, exist_ok=True)
        generate_standard_long(output, max(2, arguments.users), max(8, arguments.days))
        print(f"generated: {output} ({output.stat().st_size:,} bytes)")
        return

    outputs = (
        (OUTPUT_DIR / "multi-unit-load-15min-long.csv", generate_standard_long),
        (OUTPUT_DIR / "multi-unit-load-30min-custom-columns.csv", generate_custom_long),
        (OUTPUT_DIR / "multi-unit-load-15min-wide.csv", generate_wide),
    )
    for output, generator in outputs:
        generator(output)
        print(f"generated: {output.name} ({output.stat().st_size:,} bytes)")


if __name__ == "__main__":
    main()
