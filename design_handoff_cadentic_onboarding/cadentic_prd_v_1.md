# Product Requirements Document

## Cadentic

This is a longevity-first structured training system (MVP)

---

## 1. Executive Summary

Cadentic is a longevity focused fitness app that delivers structured training programs organized across three layers:

1. Mesocycle
2. Weekly structure
3. Daily prescription

For MVP, mesocycles are fixed once approved. Adaptation is limited to controlled daily adjustments based on logged execution and explicit athlete feedback.

Cadentic is not a lifestyle correction tool, not a behavioral enforcement system, and not a recovery diagnostic engine. It supports disciplined athletes by reducing planning overhead while preserving full athlete agency.

Core principle:
Structure first. Light autoregulation second. Athlete agency always.

---

## 2. Problem Statement

Most fitness apps either:

- Provide static programs with no flexibility
- Overpromise adaptive intelligence but deliver badly designed plans



Disciplined athletes need:

- Clear mesocycle structure
- Transparent progression
- Minimal planning friction
- Sensible day to day adjustments when sessions deviate

Cadentic solves this by combining structured programming with bounded autoregulation.

---

## 3. Product Vision

Cadentic becomes a structured AI programming engine for longevity minded athletes who:

- Value long term performance
- Accept personal responsibility for sleep and lifestyle
- Want high quality programming without constant manual planning

Cadentic optimizes programming quality, not human behavior.

---

## 4. Target User

Primary:

Any athlete that want to strain balance with a longevity and well-being first approach (team sports, lifters, endurance athletes, longevity athletes)

---

## 5. Core Product Architecture

### 5.1 Planning Hierarchy

1. **Mesocycle 4 to 16 weeks:** defined and locked during onboarding, no structural adaptation during cycle in MVP
2. **Weekly Plan:** defines distribution of strength, endurance, mobility, recovery game/team days, rest
3. **Daily Prescription:** exact workout definition including exercises, sets, reps, weights, duration, tempo, rest

---

## 6. Core System Inputs (MVP)

### 6.1 Static Inputs

- Age, sex, height, weight, experience level, current fitness level
- Injuries and constraints
- Long term goals
- Weekly or one-time blockers such as game day or team practices

### 6.2 Dynamic Inputs

- Logged sets, reps, load, session completion status&#x20;
- &#x20;Missed sessions
- Sleep, HRV, RHR and wearable data are excluded from MVP adaptive logic

---

## 7. Adaptive Logic (descoped from MVP)

This will not be implemented in the MVP. Later version will try to intelligently spread missed excercises to other days respected caps and regen/strain constraints.

### 7.1 Week 1 Baseline

- No adaptation
- Collect execution patterns
- Establish baseline completion and effort trends

### 7.2 Mesocycle Behavior

- Mesocycle structure remains fixed
- No mid cycle structural changes
- Standard build to deload ratios defined at onboarding

### 7.3 Daily Adjustments

no adjustments in MVP phase, logic to change based on sleep and recovery scores or missed excercises is worked out later.



---

## 8. Onboarding Flow

### Step 1: Base Data

collect base data (define above)

### Step 2: Goal Interview

Conversational agent chcat flow to define:

- Long term goals ("I want to dunk and I want elite level endurance”)
- Performance priorities ("endurance is more important than dunking")
- Trade offs (“I want Longevity first, so pushing into unhealthy territory to max performance is inacceptable”)
- Injuries (“I have a dislocated disk in the lower back and i have ankle instability”)
- Practices (“I wanna train in the gym, I have access to a Olympic Lifting area, I train endurance in the pool”)

### Step 3: Blockers

Game days, team practices or other blockers. Can be one time or recurring. Events have strain attribute light/medium/hard to give weekly planning engine the necessary contraint value.

### Step 4: Mesocycle Proposal

App generates first mesocycle including duration, phase structure, weekly distribution, planned deload timing.

We don't know yet, how we will enable athlete modifications. We need to see the visualization of the mesocycle and the pertinent attributes first.



Onboarding ends when mesocycle is approved.

---

## 9. Weekly Overview Screen

Displays:

- Current week number
- Training distribution (which workout type on which day)
- Planned intensity blocks
- Game days, team practice days, other blockers
- Rest days

---

## 10. Mesocycle Overview Screen

Displays:

- Current phase
- &#x20;Build or deload position
- Completion percentage
- Focus of adaptation (For example: strength, endurance, power, or recovery.)

---

## 11. Daily Prescription

Calculated each morning.

Push notification prompts athlete to review.

Displayed:

- Workout duration
- Exercise list
- Sets, reps, weights

Athlete retains full override control.

Overrides are logged and inform subsequent daily adjustments.

---

## 12. Intra Workout Mode

Session screen displays:

- completes exercises and sets
- Current exercise
- Target sets and reps, target load, current set number&#x20;
- Rest timer

Athlete logs actual execution.

If session ends early, system records partial completion and uses it as input for next session logic. If athlete stops logging, by the next day, the app assumes the training stopped where logging stopped and prescribes based on that.

---

## 13. Post Mesocycle

At completion:

System evaluates:

- Adherence rate, meaning completion
- interview how athelete felt, if he felt more or less would be good
- reaffirm that doing more above prescribed and negotiated term could be slightly beneficial for performance, which is what pros stive for but will be deterimental to longevity

Next mesocycle is proposed and negotiated through another AI based interview.

---

## 14. Explicit Non Goals for MVP

- No wearable integration required
- No HRV based adaptation&#x20;
- No sleep based logic
- No probabilistic fatigue modeling
- No behavioral enforcement (how would that even work, not even a human coach can achieve this)

## 16. Future Exploration (Post MVP)

- Structured CNS load modeling
- Wearable integration
- Probabilistic adaptive layer
- Mesocycle level adaptation
- Performance trend inference

