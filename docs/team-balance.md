# Team balancing: objective and verification

The objective is `abs(average(A) - average(B))` with exactly five distinct players per team. Since both teams have five players, this is `abs(sum(A) - sum(B)) / 5`. Sorting by total difference and sorting by average difference therefore give exactly the same ranking. Player ratings are not rounded to the one decimal place shown in the interface before searching.

## Exact search

For ten attendees there are 126 distinct splits after treating a swap of A and B as the same split. For larger pools, the solver creates every five-player subset, records its sum and membership, and sorts the subsets by sum. It examines disjoint pairs and retains the three smallest differences. Extra attendees become substitutes for that particular option.

For a fixed first subset, later sorted subsets have nondecreasing differences. Once the difference reaches the current third-best difference, none of the later pairs can improve the top three. This is a valid bound regardless of which players overlap. Every valid pair is either checked or ruled out by that bound. Equally balanced splits may occupy more than one option; swapping the two sides is never a new option.

Both the Java and PHP backends now use exact search for up to 32 attendees. The former random-sampling fallback above 24 attendees has been removed. More than 32 attendees returns a clear error asking for a smaller selection; the explicitly random shuffle remains available. This bound keeps the search suitable for shared hosting. Java uses 64-bit membership masks, including tests that require player index 31 in the optimal result.

Scores use the existing floating-point database values. Exhaustive numerical comparisons use a tolerance of 1e-9 average-rating points; analytical binary-fraction cases use 1e-14. This is numerical optimization of the stored scores, not a prediction that the teams will perform equally on the pitch. With more than ten attendees the objective may choose a different bench for each option; it does not optimize fair bench rotation, positions or chemistry.

## Alternatives considered

- Greedy assignment to the currently weaker team is fast but can miss a better split. The regression fixture `[89,87,77,67,62,32,24,21,16,14]` has an optimal average difference of 0.2.
- Randomly sampling lineups cannot certify that an unseen lineup is worse. It was removed from balanced generation.
- Exhaustive enumeration of every pair is useful as an independent test oracle, but does unnecessary work in production. The sorted-subset bound retains exactness while pruning pairs that cannot improve the answer.

## Reproducible checks

Run `./gradlew test` and `php sigma/tests/teams_test.php` (on Windows, use `./gradlew.bat test`).

Each backend compares all three results with a separate, unpruned integer-arithmetic oracle across 60 seeded pools of 10–14 attendees. Coverage includes fractional scores, equal ratings, duplicates under A/B swapping, input-order invariance and preservation of attendees/substitutes. Existing tests also cover all 43,758 ten-player subsets of the default Java catalogue.

For 25 and 32 attendees, a powers-of-two fixture has analytically known top-three sum differences of 31, 47 and 47 scaled units. These cases cannot exit on a zero difference and check the former sampling boundary. Oversized selections must fail explicitly, while random mode remains usable.

Local PHP 8.2 validation with a 128 MB limit took approximately 0.13 seconds for the 25-player analytical fixture and 0.55 seconds for 32 players, with 66 MB peak memory. These are measurements from the development machine, not latency guarantees for Sigma. Both backends must continue passing these checks when the algorithm changes.
