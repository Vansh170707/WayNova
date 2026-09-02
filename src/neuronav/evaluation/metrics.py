import numpy as np


def path_length(positions_2d: np.ndarray) -> float:
    """Cumulative distance travelled along a (N,2+) reference trajectory."""
    diffs = np.diff(positions_2d[:, :2], axis=0)
    return float(np.sum(np.linalg.norm(diffs, axis=1)))


def drift_ratio(estimated: np.ndarray, reference: np.ndarray, use_max: bool = False) -> float:
    """SIH primary criterion: position error / distance travelled (target < 10%).

    estimated, reference: (N,2+) arrays, same timestamps, East/North (Up ignored).
    """
    err = np.linalg.norm(estimated[:, :2] - reference[:, :2], axis=1)
    error = float(np.max(err)) if use_max else float(err[-1])
    dist = path_length(reference)
    if dist <= 0:
        return float("nan")
    return error / dist


def ate_rmse(estimated: np.ndarray, reference: np.ndarray) -> float:
    """Absolute trajectory error: RMSE of position error over the window (East/North)."""
    err = np.linalg.norm(estimated[:, :2] - reference[:, :2], axis=1)
    return float(np.sqrt(np.mean(err ** 2)))


def final_position_error(estimated: np.ndarray, reference: np.ndarray) -> float:
    return float(np.linalg.norm(estimated[-1, :2] - reference[-1, :2]))
