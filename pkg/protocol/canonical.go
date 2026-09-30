package protocol

import (
	"bytes"
	"encoding/json"
	"fmt"
	"sort"
	"strconv"
)

// CanonicalizeJSON converts any JSON bytes or Go struct into deterministic canonical JSON (RFC-8785 inspired).
// All keys are recursively sorted alphabetically, strings are UTF-8 normalized, numbers formatted cleanly,
// and no trailing whitespace is emitted.
func CanonicalizeJSON(v interface{}) ([]byte, error) {
	raw, err := json.Marshal(v)
	if err != nil {
		return nil, fmt.Errorf("failed to marshal input: %w", err)
	}

	var parsed interface{}
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.UseNumber()
	if err := decoder.Decode(&parsed); err != nil {
		return nil, fmt.Errorf("failed to decode for canonicalization: %w", err)
	}

	return formatCanonical(parsed)
}

func formatCanonical(v interface{}) ([]byte, error) {
	switch val := v.(type) {
	case nil:
		return []byte("null"), nil
	case bool:
		if val {
			return []byte("true"), nil
		}
		return []byte("false"), nil
	case json.Number:
		return []byte(val.String()), nil
	case float64:
		return []byte(strconv.FormatFloat(val, 'f', -1, 64)), nil
	case int64:
		return []byte(strconv.FormatInt(val, 10)), nil
	case int:
		return []byte(strconv.Itoa(val)), nil
	case string:
		b, _ := json.Marshal(val)
		return b, nil
	case []interface{}:
		var buf bytes.Buffer
		buf.WriteByte('[')
		for i, item := range val {
			if i > 0 {
				buf.WriteByte(',')
			}
			itemBytes, err := formatCanonical(item)
			if err != nil {
				return nil, err
			}
			buf.Write(itemBytes)
		}
		buf.WriteByte(']')
		return buf.Bytes(), nil
	case map[string]interface{}:
		var keys []string
		for k := range val {
			keys = append(keys, k)
		}
		sort.Strings(keys)

		var buf bytes.Buffer
		buf.WriteByte('{')
		for i, k := range keys {
			if i > 0 {
				buf.WriteByte(',')
			}
			keyBytes, _ := json.Marshal(k)
			buf.Write(keyBytes)
			buf.WriteByte(':')
			valBytes, err := formatCanonical(val[k])
			if err != nil {
				return nil, err
			}
			buf.Write(valBytes)
		}
		buf.WriteByte('}')
		return buf.Bytes(), nil
	default:
		// Fallback
		return json.Marshal(val)
	}
}

// CanonicalJSONIndent returns canonical JSON formatted with 2 spaces indentation for human-readable golden files.
func CanonicalJSONIndent(v interface{}) ([]byte, error) {
	compact, err := CanonicalizeJSON(v)
	if err != nil {
		return nil, err
	}
	var out bytes.Buffer
	if err := json.Indent(&out, compact, "", "  "); err != nil {
		return nil, err
	}
	return out.Bytes(), nil
}
