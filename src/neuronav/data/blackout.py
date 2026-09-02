def add_blackout_window(df, t_start: float, t_end: float, time_col: str = "timestamp"):
    """Return a copy of df with blackout_mask=True inside [t_start, t_end].

    Marking only (rather than dropping GNSS rows) keeps the ground truth available
    for evaluation while letting an estimator honor the mask by refusing to consume
    GNSS during that window -- see Section 5.3 blackout curricula.
    """
    out = df.copy()
    in_window = (out[time_col] >= t_start) & (out[time_col] <= t_end)
    out.loc[in_window, "blackout_mask"] = True
    return out
