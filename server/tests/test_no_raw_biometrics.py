"""Test that the server database stores NO raw biometric data"""
from __future__ import annotations

import pytest

from app.database import Base
from app.models import (
    AggregateStressStat,
    AuditLog,
    ModelVersion,
    Organization,
    TokenBlocklist,
    User,
)


class TestNoRawBiometrics:
    """Inspect the ORM schema to prove no raw biometric columns exist."""

    # Column names that would indicate raw biometric storage
    BIOMETRIC_KEYWORDS = {
        "heart_rate", "hr_value", "bpm", "rr_interval",
        "eda_value", "eda_raw", "skin_conductance", "gsr",
        "temperature_raw", "temp_value", "skin_temp",
        "acc_x", "acc_y", "acc_z", "accelerometer",
        "feature_tensor", "raw_signal", "biometric",
        "ppg", "bvp",
    }

    # Table names that would indicate per-reading biometric storage
    BIOMETRIC_TABLE_KEYWORDS = {
        "readings", "sensor_data", "biometric_data",
        "hr_samples", "eda_samples", "temp_samples",
    }

    def test_no_biometric_columns_in_any_table(self):
        """No column in any server table should hold raw biometric data."""
        for table in Base.metadata.tables.values():
            for col in table.columns:
                col_lower = col.name.lower()
                for kw in self.BIOMETRIC_KEYWORDS:
                    assert kw not in col_lower, (
                        f"Table '{table.name}' has column '{col.name}' "
                        f"which suggests raw biometric storage (matched '{kw}')"
                    )

    def test_no_biometric_tables(self):
        """No table name should suggest per-sample biometric storage."""
        for table_name in Base.metadata.tables:
            name_lower = table_name.lower()
            for kw in self.BIOMETRIC_TABLE_KEYWORDS:
                assert kw not in name_lower, (
                    f"Table '{table_name}' suggests raw biometric storage "
                    f"(matched '{kw}')"
                )

    def test_known_tables_only(self):
        """Only expected tables should exist in the schema"""
        allowed_tables = {
            "organizations",
            "users",
            "model_versions",
            "aggregate_stress_stats",
            "audit_log",
            "token_blocklist",
        }
        actual_tables = set(Base.metadata.tables.keys())
        unexpected = actual_tables - allowed_tables
        assert not unexpected, (
            f"Unexpected tables found: {unexpected}. "
            "If intentional, add to the whitelist after verifying no raw biometrics."
        )

    def test_aggregate_stats_has_only_aggregates(self):
        """The AggregateStressStat table should NOT have per-user data."""
        table = AggregateStressStat.__table__
        col_names = {c.name for c in table.columns}

        assert "n_users" in col_names
        assert "mean_stress_score" in col_names
        assert "std_stress" in col_names

        forbidden = {"user_id", "reading_id", "score", "raw_score"}
        assert not col_names.intersection(forbidden), (
            f"AggregateStressStat should not have per-user columns: "
            f"{col_names.intersection(forbidden)}"
        )

    def test_user_table_has_no_health_data(self):
        """User table should only have auth fields, no health data."""
        table = User.__table__
        col_names = {c.name for c in table.columns}
        expected = {"id", "email", "hashed_password", "role", "organization_id", "created_at"}
        assert col_names == expected, (
            f"User table has unexpected columns: {col_names - expected}"
        )
