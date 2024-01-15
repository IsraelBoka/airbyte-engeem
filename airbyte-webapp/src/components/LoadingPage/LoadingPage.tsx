import React from "react";

import { FlexContainer } from "components/ui/Flex";

import styles from "./LoadingPage.module.scss";

export const LoadingPage: React.FC = () => {
  return <FlexContainer alignItems="center" justifyContent="center" className={styles.loadingPage} />;
};
