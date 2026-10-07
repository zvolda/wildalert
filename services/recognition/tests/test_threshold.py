from app.classifier import Recognition, apply_threshold


def test_above_threshold_is_not_low_confidence():
    result = apply_threshold(Recognition(species="wild boar", confidence=0.9), threshold=0.7)

    assert result.low_confidence is False
    assert result.species == "wild boar"


def test_below_threshold_is_low_confidence():
    result = apply_threshold(Recognition(species="fox", confidence=0.4), threshold=0.7)

    assert result.low_confidence is True


def test_exactly_at_threshold_is_not_low_confidence():
    result = apply_threshold(Recognition(species="deer", confidence=0.7), threshold=0.7)

    assert result.low_confidence is False
