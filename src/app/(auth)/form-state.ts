export interface FormState {
  error?: string;
  ok?: string;
  /** Echoed back so the field survives React resetting the form after an action. */
  identifier?: string;
}
