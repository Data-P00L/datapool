package main

import (
	"fmt"
	"os"
	"os/exec"
)

func main() {
	if len(os.Args) < 2 {
		printHelp()
		os.Exit(1)
	}

	command := os.Args[1]

	switch command {
	case "capture":
		runSubCmd("capture", os.Args[2:])
	case "contribution":
		if len(os.Args) < 3 {
			fmt.Println("Usage: dattapool contribution [calculate|issue] ...")
			os.Exit(1)
		}
		runSubCmd("contribution", os.Args[2:])
	case "claim":
		if len(os.Args) < 3 {
			fmt.Println("Usage: dattapool claim [publish|verify|inspect] ...")
			os.Exit(1)
		}
		sub := os.Args[2]
		switch sub {
		case "publish":
			runSubCmd("publish_claim", os.Args[3:])
		case "verify":
			runSubCmd("verify_claim", os.Args[3:])
		case "inspect":
			runSubCmd("capture", append([]string{"inspect"}, os.Args[3:]...))
		default:
			fmt.Printf("Unknown claim subcommand %q\n", sub)
			os.Exit(1)
		}
	case "possession":
		if len(os.Args) < 3 {
			fmt.Println("Usage: dattapool possession [challenge|verify] ...")
			os.Exit(1)
		}
		sub := os.Args[2]
		switch sub {
		case "challenge":
			runSubCmd("capture", append([]string{"possession-challenge"}, os.Args[3:]...))
		case "verify":
			runSubCmd("capture", append([]string{"possession-verify"}, os.Args[3:]...))
		default:
			fmt.Printf("Unknown possession subcommand %q\n", sub)
			os.Exit(1)
		}
	case "token":
		if len(os.Args) < 3 || os.Args[2] != "mint" {
			fmt.Println("Usage: dattapool token mint [flags]")
			os.Exit(1)
		}
		runSubCmd("mint_data_token", os.Args[3:])
	default:
		printHelp()
		os.Exit(1)
	}
}

func runSubCmd(binName string, args []string) {
	// Look for binary in bin/ or PATH
	binPath := "./bin/" + binName
	if _, err := os.Stat(binPath); os.IsNotExist(err) {
		binPath = binName
	}
	cmd := exec.Command(binPath, args...)
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	cmd.Stdin = os.Stdin
	if err := cmd.Run(); err != nil {
		if exitErr, ok := err.(*exec.ExitError); ok {
			os.Exit(exitErr.ExitCode())
		}
		fmt.Fprintf(os.Stderr, "Error executing %s: %v\n", binName, err)
		os.Exit(1)
	}
}

func printHelp() {
	fmt.Println("DattaPool Protocol CLI")
	fmt.Println("Usage:")
	fmt.Println("  dattapool capture [keygen|start|ingest|stop|inspect|quick] ...")
	fmt.Println("  dattapool claim publish --manifest <manifest.json>")
	fmt.Println("  dattapool claim verify --manifest <manifest.json> [flags]")
	fmt.Println("  dattapool claim inspect <manifest.json>")
	fmt.Println("  dattapool contribution calculate --manifest <manifest.json>")
	fmt.Println("  dattapool contribution issue --manifest <manifest.json> [flags]")
	fmt.Println("  dattapool token mint [--name DTTA] [--units <N>] [--manifest <manifest.json>]")
}
